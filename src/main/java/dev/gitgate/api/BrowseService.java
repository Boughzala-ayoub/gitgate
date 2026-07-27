package dev.gitgate.api;

import dev.gitgate.acl.AclService;
import dev.gitgate.api.Dtos.BlobDto;
import dev.gitgate.api.Dtos.BranchesDto;
import dev.gitgate.api.Dtos.TreeEntryDto;
import dev.gitgate.git.ViewService;
import dev.gitgate.store.RepoRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Read-only repository browsing, served from the <em>viewer's materialized view</em> (the
 * anonymous view for public repos, the user's view when authenticated). Because it reads the
 * filtered view and never the canonical repo, a user can never browse a path they couldn't clone.
 */
@Service
public class BrowseService {

    private static final long MAX_BLOB = 1_000_000; // 1 MB display cap

    private final RepoRepository repos;
    private final AclService acl;
    private final ViewService viewService;

    public BrowseService(RepoRepository repos, AclService acl, ViewService viewService) {
        this.repos = repos;
        this.acl = acl;
        this.viewService = viewService;
    }

    public BranchesDto branches(String user, String name) {
        requireAccess(user, name);
        try (Repository view = viewService.openView(name, user)) {
            List<String> branches = view.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).stream()
                    .map(r -> r.getName().substring(Constants.R_HEADS.length()))
                    .sorted().toList();
            String full = view.getFullBranch();
            String def = (full != null && full.startsWith(Constants.R_HEADS))
                    ? full.substring(Constants.R_HEADS.length()) : null;
            return new BranchesDto(def, branches);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<TreeEntryDto> tree(String user, String name, String ref, String path) {
        requireAccess(user, name);
        String p = normalizePath(path);
        try (Repository view = viewService.openView(name, user); RevWalk walk = new RevWalk(view)) {
            ObjectId commitId = resolveRef(view, ref);
            if (commitId == null) {
                return List.of(); // empty repo
            }
            RevTree root = walk.parseCommit(commitId).getTree();

            ObjectId treeId;
            if (p.isEmpty()) {
                treeId = root;
            } else {
                TreeWalk located = TreeWalk.forPath(view, p, root);
                if (located == null || !FileMode.TREE.equals(located.getFileMode(0))) {
                    throw new NoSuchElementException("not a directory: " + p);
                }
                treeId = located.getObjectId(0);
            }

            List<TreeEntryDto> entries = new ArrayList<>();
            try (TreeWalk t = new TreeWalk(view)) {
                t.addTree(treeId);
                t.setRecursive(false);
                while (t.next()) {
                    boolean isTree = FileMode.TREE.equals(t.getFileMode(0));
                    String childPath = p.isEmpty() ? t.getNameString() : p + "/" + t.getNameString();
                    entries.add(new TreeEntryDto(t.getNameString(), isTree ? "tree" : "blob", childPath));
                }
            }
            entries.sort(Comparator.comparingInt((TreeEntryDto e) -> e.type().equals("tree") ? 0 : 1)
                    .thenComparing(TreeEntryDto::name));
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BlobDto blob(String user, String name, String ref, String path) {
        requireAccess(user, name);
        String p = normalizePath(path);
        if (p.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        try (Repository view = viewService.openView(name, user); RevWalk walk = new RevWalk(view)) {
            ObjectId commitId = resolveRef(view, ref);
            if (commitId == null) {
                throw new NoSuchElementException("not found: " + p);
            }
            RevTree root = walk.parseCommit(commitId).getTree();
            TreeWalk located = TreeWalk.forPath(view, p, root);
            if (located == null) {
                throw new NoSuchElementException("not found: " + p);
            }
            if (FileMode.TREE.equals(located.getFileMode(0))) {
                throw new IllegalArgumentException("is a directory: " + p);
            }
            ObjectLoader loader = view.open(located.getObjectId(0), Constants.OBJ_BLOB);
            long size = loader.getSize();
            if (size > MAX_BLOB) {
                return new BlobDto(p, size, false, true, null);
            }
            byte[] bytes = loader.getBytes();
            boolean binary = isBinary(bytes);
            return new BlobDto(p, size, binary, false, binary ? null : new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void requireAccess(String user, String name) {
        if (repos.findByName(stripGit(name)).isEmpty()) {
            throw new NoSuchElementException("repository not found: " + name);
        }
        if (acl.resolve(user, name).isEmpty()) {
            throw new ForbiddenException("no access to repository: " + name);
        }
    }

    private ObjectId resolveRef(Repository view, String ref) throws IOException {
        if (ref == null || ref.isBlank()) {
            return view.resolve(Constants.HEAD);
        }
        ObjectId id = view.resolve(ref);
        return id != null ? id : view.resolve(Constants.R_HEADS + ref);
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String stripGit(String name) {
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    private boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
