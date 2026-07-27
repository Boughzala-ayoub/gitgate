package dev.gitgate.git;

import dev.gitgate.acl.GlobMatcher;
import dev.gitgate.acl.ReadView;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Applies a user's push (made against their filtered view) back onto canonical history.
 *
 * <p>For each pushed commit, the canonical commit is reconstructed by <em>merging</em>:
 * readable paths come from the user's new tree, while every path the user can't see is
 * carried over unchanged from the canonical parent. This makes it structurally impossible
 * for a push to delete or alter hidden files. A write-ACL additionally rejects changes to
 * readable-but-not-writable paths. The canonical ref is then fast-forward updated.
 */
@Component
public class PushReconciler {

    private static final Logger log = LoggerFactory.getLogger(PushReconciler.class);

    private final ViewMaterializer materializer;

    public PushReconciler(ViewMaterializer materializer) {
        this.materializer = materializer;
    }

    /** Pre-receive entry point: reconcile every command, marking rejects on the command itself. */
    public void handle(Repository canonical, Repository view, ReadView access,
                       Collection<ReceiveCommand> commands) {
        Map<ObjectId, ObjectId> viewToCanonical;
        try {
            Map<ObjectId, ObjectId> forward = materializer.materialize(canonical, view, access.matcher());
            viewToCanonical = new HashMap<>();
            forward.forEach((canon, viewId) -> viewToCanonical.put(viewId, canon));
        } catch (IOException e) {
            commands.forEach(c -> c.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "server error: " + e.getMessage()));
            return;
        }

        for (ReceiveCommand cmd : commands) {
            try {
                reconcile(canonical, view, access, viewToCanonical, cmd);
            } catch (ReconcileException e) {
                cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, e.getMessage());
            } catch (IOException e) {
                cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "server error: " + e.getMessage());
            }
        }
    }

    private void reconcile(Repository canonical, Repository view, ReadView access,
                           Map<ObjectId, ObjectId> viewToCanonical, ReceiveCommand cmd) throws IOException {
        if (cmd.getType() == ReceiveCommand.Type.DELETE) {
            throw new ReconcileException("branch deletion is not supported");
        }

        ObjectId oldView = cmd.getOldId();
        ObjectId newView = cmd.getNewId();
        boolean creating = ObjectId.zeroId().equals(oldView);

        ObjectId baseCanonical = creating ? null : viewToCanonical.get(oldView);
        if (!creating && baseCanonical == null) {
            throw new ReconcileException("stale base for " + cmd.getRefName() + "; pull and retry");
        }

        Map<ObjectId, ObjectId> commitMap = new HashMap<>(viewToCanonical);
        Map<ObjectId, ObjectId> treeOfCommit = new HashMap<>();
        ObjectId newCanonical;

        try (RevWalk viewWalk = new RevWalk(view);
             RevWalk canonWalk = new RevWalk(canonical);
             ObjectReader viewReader = view.newObjectReader();
             ObjectReader canonReader = canonical.newObjectReader();
             ObjectInserter inserter = canonical.newObjectInserter()) {

            newCanonical = reconstruct(newView, canonical, view, access, commitMap, treeOfCommit,
                    viewWalk, canonWalk, inserter, canonReader, viewReader);
            inserter.flush();
        }

        RefUpdate update = canonical.updateRef(cmd.getRefName());
        update.setExpectedOldObjectId(creating ? ObjectId.zeroId() : baseCanonical);
        update.setNewObjectId(newCanonical);
        RefUpdate.Result result = update.update();
        switch (result) {
            case NEW, FAST_FORWARD, FORCED, NO_CHANGE ->
                    log.debug("Reconciled {} -> canonical {}", cmd.getRefName(), newCanonical.name());
            default -> throw new ReconcileException(
                    "could not update " + cmd.getRefName() + " (" + result + "); pull and retry");
        }
        // Command left to proceed: ReceivePack now fast-forwards the view ref, so the client sees success.
    }

    /**
     * Returns the canonical-commit id for a pushed view-commit, rebuilding (and memoizing) as needed.
     *
     * <p>Iterative rather than recursive: a first push carries the repo's entire history, which is
     * deeper than the JVM stack can hold.
     */
    private ObjectId reconstruct(ObjectId root, Repository canonical, Repository view, ReadView access,
                                 Map<ObjectId, ObjectId> commitMap, Map<ObjectId, ObjectId> treeOfCommit,
                                 RevWalk viewWalk, RevWalk canonWalk, ObjectInserter inserter,
                                 ObjectReader canonReader, ObjectReader viewReader) throws IOException {
        Deque<ObjectId> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            ObjectId viewCommitId = stack.peek();
            if (commitMap.containsKey(viewCommitId)) {
                stack.pop();
                continue;
            }

            RevCommit viewCommit = viewWalk.parseCommit(viewCommitId);

            boolean awaitingParents = false;
            for (RevCommit parent : viewCommit.getParents()) {
                if (!commitMap.containsKey(parent.getId())) {
                    stack.push(parent.getId());
                    awaitingParents = true;
                }
            }
            if (awaitingParents) {
                continue;
            }

            ObjectId[] canonParents = new ObjectId[viewCommit.getParentCount()];
            for (int i = 0; i < canonParents.length; i++) {
                canonParents[i] = commitMap.get(viewCommit.getParent(i).getId());
            }

            // Base = first parent's canonical tree (the hidden content we must preserve).
            ObjectId baseCanonicalTree = null;
            ObjectId viewParentTree = null;
            if (canonParents.length > 0) {
                ObjectId p0 = canonParents[0];
                baseCanonicalTree = treeOfCommit.containsKey(p0)
                        ? treeOfCommit.get(p0)
                        : canonWalk.parseCommit(p0).getTree();
                viewParentTree = viewWalk.parseCommit(viewCommit.getParent(0).getId()).getTree();
            }

            enforceWriteAcl(view, viewParentTree, viewCommit.getTree(), access.writeMatcher());

            ObjectId mergedTree = mergeTree(canonical, view, baseCanonicalTree, viewCommit.getTree(),
                    access.matcher(), inserter, canonReader, viewReader, "");
            if (mergedTree == null) {
                mergedTree = inserter.insert(new TreeFormatter());
            }

            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(mergedTree);
            builder.setParentIds(canonParents);
            builder.setAuthor(viewCommit.getAuthorIdent());
            builder.setCommitter(viewCommit.getCommitterIdent());
            builder.setMessage(viewCommit.getFullMessage());
            builder.setEncoding(viewCommit.getEncoding());

            ObjectId canonId = inserter.insert(builder);
            commitMap.put(viewCommitId, canonId);
            treeOfCommit.put(canonId, mergedTree);
            stack.pop();
        }

        return commitMap.get(root);
    }

    /** Rejects the push if it changes any readable path the user is not allowed to write. */
    private void enforceWriteAcl(Repository view, ObjectId parentTree, ObjectId newTree, GlobMatcher writeMatcher)
            throws IOException {
        try (TreeWalk walk = new TreeWalk(view)) {
            if (parentTree == null) {
                walk.addTree(newTree);
                walk.setRecursive(true);
                while (walk.next()) {
                    requireWritable(walk.getPathString(), writeMatcher);
                }
            } else {
                walk.addTree(parentTree);
                walk.addTree(newTree);
                walk.setRecursive(true);
                walk.setFilter(TreeFilter.ANY_DIFF);
                while (walk.next()) {
                    requireWritable(walk.getPathString(), writeMatcher);
                }
            }
        }
    }

    private void requireWritable(String path, GlobMatcher writeMatcher) {
        if (!writeMatcher.isReadable(path)) {
            throw new ReconcileException("permission denied: you cannot write '" + path + "'");
        }
    }

    /**
     * Builds a canonical tree where readable entries come from {@code viewTree} and every
     * other entry is preserved from {@code canonTree}. Readable blobs that are new (only in
     * the view) are copied into the canonical object store.
     *
     * @return the merged tree id, or {@code null} if the tree ends up empty
     */
    private ObjectId mergeTree(Repository canonical, Repository view, ObjectId canonTree, ObjectId viewTree,
                               GlobMatcher readMatcher, ObjectInserter inserter,
                               ObjectReader canonReader, ObjectReader viewReader, String prefix) throws IOException {
        Map<String, Entry> canonEntries = readEntries(canonical, canonTree);
        Map<String, Entry> viewEntries = readEntries(view, viewTree);

        List<Out> out = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>();
        names.addAll(canonEntries.keySet());
        names.addAll(viewEntries.keySet());

        for (String name : names) {
            Entry c = canonEntries.get(name);
            Entry v = viewEntries.get(name);
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            boolean cTree = c != null && FileMode.TREE.equals(c.mode());
            boolean vTree = v != null && FileMode.TREE.equals(v.mode());

            if (cTree || vTree) {
                ObjectId merged = mergeTree(canonical, view,
                        cTree ? c.id() : null, vTree ? v.id() : null,
                        readMatcher, inserter, canonReader, viewReader, path);
                if (merged != null) {
                    out.add(new Out(name, FileMode.TREE, merged));
                }
            } else if (readMatcher.isReadable(path)) {
                // Readable leaf: take the user's version (absent => they deleted it).
                if (v != null) {
                    copyBlobIfMissing(canonReader, viewReader, inserter, v.id());
                    out.add(new Out(name, v.mode(), v.id()));
                }
            } else if (c != null) {
                // Hidden leaf: preserve canonical unchanged.
                out.add(new Out(name, c.mode(), c.id()));
            }
        }

        if (out.isEmpty()) {
            return null;
        }
        out.sort(GIT_TREE_ORDER);
        TreeFormatter formatter = new TreeFormatter();
        for (Out o : out) {
            formatter.append(o.name(), o.mode(), o.id());
        }
        return inserter.insert(formatter);
    }

    private Map<String, Entry> readEntries(Repository repo, ObjectId treeId) throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (treeId == null) {
            return entries;
        }
        try (TreeWalk walk = new TreeWalk(repo)) {
            walk.addTree(treeId);
            walk.setRecursive(false);
            while (walk.next()) {
                entries.put(walk.getNameString(), new Entry(walk.getFileMode(0), walk.getObjectId(0)));
            }
        }
        return entries;
    }

    private void copyBlobIfMissing(ObjectReader canonReader, ObjectReader viewReader,
                                   ObjectInserter inserter, ObjectId blobId) throws IOException {
        if (canonReader.has(blobId)) {
            return;
        }
        ObjectLoader loader = viewReader.open(blobId, Constants.OBJ_BLOB);
        inserter.insert(Constants.OBJ_BLOB, loader.getBytes());
    }

    private record Entry(FileMode mode, ObjectId id) {}

    private record Out(String name, FileMode mode, ObjectId id) {}

    /** Git's tree-entry ordering: byte compare, treating directory names as if suffixed with '/'. */
    private static final Comparator<Out> GIT_TREE_ORDER = (a, b) ->
            baseNameCompare(a.name(), FileMode.TREE.equals(a.mode()), b.name(), FileMode.TREE.equals(b.mode()));

    private static int baseNameCompare(String n1, boolean dir1, String n2, boolean dir2) {
        byte[] a = n1.getBytes(StandardCharsets.UTF_8);
        byte[] b = n2.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int c = (a[i] & 0xff) - (b[i] & 0xff);
            if (c != 0) {
                return c;
            }
        }
        int c1 = a.length > len ? (a[len] & 0xff) : (dir1 ? '/' : 0);
        int c2 = b.length > len ? (b[len] & 0xff) : (dir2 ? '/' : 0);
        return c1 - c2;
    }
}
