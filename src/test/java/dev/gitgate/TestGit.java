package dev.gitgate;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bare-repo fixtures for the engine tests: build history, then inspect it. */
public final class TestGit {

    /** Fixed identity so commit ids are reproducible across runs. */
    private static final PersonIdent WHO = new PersonIdent("Test", "test@example.com", 1_700_000_000_000L, 0);

    public static final String BRANCH = "refs/heads/main";

    private TestGit() {
    }

    /** Creates an empty bare repository whose HEAD tracks {@code main}. */
    public static Repository bare(Path gitDir) throws IOException {
        Repository db = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setBare().build();
        db.create(true);
        RefUpdate head = db.updateRef(Constants.HEAD);
        head.disableRefLog();
        head.link(BRANCH);
        return db;
    }

    /**
     * Commits {@code files} as a complete snapshot on {@code main}, parented on the current tip.
     *
     * @return the new commit id
     */
    public static ObjectId commit(Repository db, String message, Map<String, String> files) throws IOException {
        Ref existing = db.exactRef(BRANCH);
        ObjectId parent = existing == null ? null : existing.getObjectId();

        ObjectId commitId;
        try (ObjectInserter inserter = db.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB,
                        file.getValue().getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            if (parent != null) {
                commit.setParentId(parent);
            }
            commit.setAuthor(WHO);
            commit.setCommitter(WHO);
            commit.setMessage(message);
            commitId = inserter.insert(commit);
            inserter.flush();
        }

        RefUpdate update = db.updateRef(BRANCH);
        update.setNewObjectId(commitId);
        update.setForceUpdate(true);
        update.update();

        // A bare repo starts with HEAD -> refs/heads/master; point it at the branch we commit on,
        // or clones have no default branch to check out.
        RefUpdate head = db.updateRef(Constants.HEAD);
        head.disableRefLog();
        head.link(BRANCH);
        return commitId;
    }

    /** The repository's {@code main} tip, or null if unborn. */
    public static ObjectId head(Repository db) throws IOException {
        Ref ref = db.exactRef(BRANCH);
        return ref == null ? null : ref.getObjectId();
    }

    /** Every file path reachable from {@code commitId}'s tree. */
    public static SortedSet<String> paths(Repository db, ObjectId commitId) throws IOException {
        TreeSet<String> found = new TreeSet<>();
        try (RevWalk walk = new RevWalk(db); TreeWalk tree = new TreeWalk(db)) {
            tree.addTree(walk.parseCommit(commitId).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                found.add(tree.getPathString());
            }
        }
        return found;
    }

    /** Contents of {@code path} at {@code commitId}, or null if it isn't there. */
    public static String content(Repository db, ObjectId commitId, String path) throws IOException {
        try (RevWalk walk = new RevWalk(db)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk found = TreeWalk.forPath(db, path, commit.getTree())) {
                if (found == null) {
                    return null;
                }
                return new String(db.open(found.getObjectId(0), Constants.OBJ_BLOB).getBytes(),
                        StandardCharsets.UTF_8);
            }
        }
    }

    /** Points {@code view} at {@code canonical}'s object store, as ViewService does in production. */
    public static void linkAlternate(Repository view, Repository canonical) throws IOException {
        Path alternates = view.getDirectory().toPath().resolve("objects").resolve("info").resolve("alternates");
        Files.createDirectories(alternates.getParent());
        Files.writeString(alternates,
                canonical.getDirectory().toPath().resolve("objects").toAbsolutePath() + System.lineSeparator());
    }

    /** The demo repository used across the tests: two teams, a shared README, and a secret. */
    public static Map<String, String> demoFiles() {
        return Map.of(
                "frontend/app.js", "console.log('ui');\n",
                "backend/db.js", "connect('postgres');\n",
                "README.md", "# app\n",
                "TOPSECRET.txt", "launch codes\n");
    }
}
