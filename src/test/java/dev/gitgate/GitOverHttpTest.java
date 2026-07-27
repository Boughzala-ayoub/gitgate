package dev.gitgate;

import dev.gitgate.git.RepositoryService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole system over the wire: real git clients hitting the real smart-HTTP endpoint.
 *
 * <p>One canonical repo, three users, three different sets of bytes — and a push that
 * cannot reach what the pusher cannot see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitOverHttpTest {

    /** Scratch storage under target/ — no cleanup races with the still-running server on Windows. */
    private static final Path DATA = Path.of("target", "test-data", "git-over-http");

    private static final AtomicInteger CLONE_COUNTER = new AtomicInteger();

    /** The fixture commit every test starts from. */
    private static ObjectId initialCommit;

    @DynamicPropertySource
    static void serverStorage(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:git-http-test;DB_CLOSE_DELAY=-1");
        registry.add("gitgate.repo-root", () -> DATA.resolve("repos").toString());
        registry.add("gitgate.views-root", () -> DATA.resolve("views").toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RepositoryService repositories;

    /** One clean slate per run — before the server has opened anything under it. */
    @BeforeAll
    static void clearStorage() throws IOException {
        deleteRecursively(DATA);
    }

    /**
     * Puts repo "app" (seeded in acl.yml with alice/bob memberships) back to its initial commit
     * before every test. Rewinding the ref rather than deleting files avoids fighting the running
     * server for open handles; leftover objects from a pushed test are simply unreferenced.
     */
    @BeforeEach
    void seedCanonicalRepo() throws IOException {
        try (Repository canonical = repositories.open("app")) {
            if (initialCommit == null) {
                initialCommit = TestGit.commit(canonical, "initial", TestGit.demoFiles());
                return;
            }
            RefUpdate rewind = canonical.updateRef(TestGit.BRANCH);
            rewind.setNewObjectId(initialCommit);
            rewind.setForceUpdate(true);
            rewind.update();
        }
    }

    @Test
    void eachUserClonesOnlyTheirOwnSliceOfTheSameUrl() throws Exception {
        assertEquals(Set.of("frontend/app.js", "README.md"), clonedPaths("alice", "alice123"));
        assertEquals(Set.of("backend/db.js", "README.md"), clonedPaths("bob", "bob123"));
        assertEquals(Set.of("frontend/app.js", "backend/db.js", "README.md", "TOPSECRET.txt"),
                clonedPaths("admin", "admin123"));
    }

    @Test
    void aHiddenBlobIsNeverTransmittedEvenBySha() throws Exception {
        ObjectId secret;
        try (Repository canonical = repositories.open("app")) {
            secret = blobId(canonical, "TOPSECRET.txt");
        }

        try (Git clone = cloneAs("alice", "alice123")) {
            assertFalse(clone.getRepository().getObjectDatabase().has(secret),
                    "alice's clone must not contain the hidden object, even by its exact id");
        }
    }

    @Test
    void anonymousCloneOfAPrivateRepoIsRejected() {
        Path target = freshCloneDir();
        assertThrows(TransportException.class, () ->
                Git.cloneRepository().setURI(cloneUrl()).setDirectory(target.toFile()).call());
    }

    @Test
    void badCredentialsAreRejected() {
        Path target = freshCloneDir();
        assertThrows(TransportException.class, () -> Git.cloneRepository()
                .setURI(cloneUrl())
                .setDirectory(target.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("alice", "wrong"))
                .call());
    }

    @Test
    void pushIsMergedBackWithoutTouchingHiddenFiles() throws Exception {
        try (Git clone = cloneAs("alice", "alice123")) {
            writeFile(clone, "frontend/app.js", "console.log('ui v2');\n");
            commitAll(clone, "alice: ship a tweak");

            assertEquals(RemoteRefUpdate.Status.OK, pushAs(clone, "alice", "alice123"));
        }

        try (Repository canonical = repositories.open("app")) {
            ObjectId tip = TestGit.head(canonical);
            assertEquals(Set.of("frontend/app.js", "backend/db.js", "README.md", "TOPSECRET.txt"),
                    TestGit.paths(canonical, tip));
            assertEquals("console.log('ui v2');\n", TestGit.content(canonical, tip, "frontend/app.js"));
            assertEquals("launch codes\n", TestGit.content(canonical, tip, "TOPSECRET.txt"),
                    "a push from a view that cannot see this file must not change it");
        }
    }

    @Test
    void pushToAPathTheUserMayReadButNotWriteIsRejected() throws Exception {
        try (Git clone = cloneAs("alice", "alice123")) {
            writeFile(clone, "README.md", "# hijacked\n");
            commitAll(clone, "alice: edit the readme");

            assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON, pushAs(clone, "alice", "alice123"));
        }

        try (Repository canonical = repositories.open("app")) {
            assertEquals("# app\n", TestGit.content(canonical, TestGit.head(canonical), "README.md"));
        }
    }

    @Test
    void bobsPushCannotSeeOrDisturbAlicesHalf() throws Exception {
        try (Git clone = cloneAs("bob", "bob123")) {
            writeFile(clone, "backend/db.js", "connect('mysql');\n");
            commitAll(clone, "bob: swap the database");

            assertEquals(RemoteRefUpdate.Status.OK, pushAs(clone, "bob", "bob123"));
        }

        try (Repository canonical = repositories.open("app")) {
            ObjectId tip = TestGit.head(canonical);
            assertEquals("connect('mysql');\n", TestGit.content(canonical, tip, "backend/db.js"));
            assertEquals("console.log('ui');\n", TestGit.content(canonical, tip, "frontend/app.js"));
            assertEquals("launch codes\n", TestGit.content(canonical, tip, "TOPSECRET.txt"));
        }
    }

    @Test
    void aViewThatLostItsAlternatesLinkRepairsItself() throws Exception {
        Set<String> expected = clonedPaths("alice", "alice123"); // materialize the view first

        try (var paths = Files.walk(DATA.resolve("views"))) {
            paths.filter(path -> path.getFileName().toString().equals("alternates")).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        assertEquals(expected, clonedPaths("alice", "alice123"),
                "a view missing its object link must repair itself, not serve broken packs");
    }

    // --- helpers -------------------------------------------------------------------------

    private String cloneUrl() {
        return "http://localhost:" + port + "/app.git";
    }

    private Git cloneAs(String user, String token) throws Exception {
        return Git.cloneRepository()
                .setURI(cloneUrl())
                .setDirectory(freshCloneDir().toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token))
                .call();
    }

    private Set<String> clonedPaths(String user, String token) throws Exception {
        try (Git clone = cloneAs(user, token)) {
            Repository db = clone.getRepository();
            return TestGit.paths(db, db.resolve("HEAD"));
        }
    }

    private RemoteRefUpdate.Status pushAs(Git clone, String user, String token) throws Exception {
        Iterable<PushResult> results = clone.push()
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token))
                .call();
        RemoteRefUpdate update = results.iterator().next().getRemoteUpdates().iterator().next();
        return update.getStatus();
    }

    private void writeFile(Git clone, String path, String content) throws IOException {
        Path file = clone.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void commitAll(Git clone, String message) throws Exception {
        clone.add().addFilepattern(".").call();
        clone.commit()
                .setAuthor("Test", "test@example.com")     // never depend on the machine's git config
                .setCommitter("Test", "test@example.com")
                .setMessage(message)
                .call();
    }

    private ObjectId blobId(Repository db, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(db, path, revTree(db))) {
            assertTrue(walk != null, "fixture missing " + path);
            return walk.getObjectId(0);
        }
    }

    private ObjectId revTree(Repository db) throws IOException {
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
            return walk.parseCommit(TestGit.head(db)).getTree().getId();
        }
    }

    private Path freshCloneDir() {
        return DATA.resolve("clones").resolve("c" + CLONE_COUNTER.incrementAndGet());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort: a leftover handle just means the next run picks a new name
                }
            });
        }
    }
}
