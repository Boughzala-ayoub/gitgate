package dev.gitgate;

import dev.gitgate.api.Dtos;
import dev.gitgate.api.RepoService;
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
import org.eclipse.jgit.util.FileUtils;
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
import java.util.List;
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
    @Autowired
    private RepoService repoService;

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
            } else {
                RefUpdate rewind = canonical.updateRef(TestGit.BRANCH);
                rewind.setNewObjectId(initialCommit);
                rewind.setForceUpdate(true);
                rewind.update();
            }
        }
        // Tests that re-scope a collaborator would otherwise leak into whatever runs next, so
        // put alice and bob back to their acl.yml scopes every time.
        repoService.addCollaborator("admin", "app", "alice", "WRITE", List.of(
                new Dtos.ScopeDto("frontend", true, true), new Dtos.ScopeDto("*.md", true, false)));
        repoService.addCollaborator("admin", "app", "bob", "WRITE", List.of(
                new Dtos.ScopeDto("backend", true, true), new Dtos.ScopeDto("*.md", true, false)));
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

    /**
     * The exclusion case, end to end: alice is granted the whole repository but one file is
     * carved out. A clone must not contain it — and must not contain the object either, so it
     * can't be recovered by SHA.
     */
    @Test
    void anExcludedFileNeverReachesTheCollaboratorWhoIsOtherwiseGrantedEverything() throws Exception {
        ObjectId secret;
        try (Repository canonical = repositories.open("app")) {
            secret = blobId(canonical, "TOPSECRET.txt");
        }
        grantWholeRepoExcept("alice", "TOPSECRET.txt");

        try (Git clone = cloneAs("alice", "alice123")) {
            Repository db = clone.getRepository();
            Set<String> got = TestGit.paths(db, db.resolve("HEAD"));

            assertEquals(Set.of("frontend/app.js", "backend/db.js", "README.md"), got,
                    "everything except the excluded file");
            assertFalse(db.getObjectDatabase().has(secret),
                    "the excluded blob must not be transmitted at all");
        }
    }

    @Test
    void anExcludedPathCannotBePushedEither() throws Exception {
        grantWholeRepoExcept("alice", "TOPSECRET.txt");

        try (Git clone = cloneAs("alice", "alice123")) {
            // She can't see it, so she can only try to create it afresh.
            writeFile(clone, "TOPSECRET.txt", "rewritten by alice\n");
            commitAll(clone, "alice: overwrite the file she cannot see");
            assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON, pushAs(clone, "alice", "alice123"));
        }

        try (Repository canonical = repositories.open("app")) {
            assertEquals("launch codes\n", TestGit.content(canonical, TestGit.head(canonical), "TOPSECRET.txt"));
        }
    }

    /** Replaces a collaborator's scopes with "everything, minus one path". */
    private void grantWholeRepoExcept(String username, String excludedPath) {
        repoService.addCollaborator("admin", "app", username, "WRITE", List.of(
                new Dtos.ScopeDto("**", true, true, false),
                new Dtos.ScopeDto(excludedPath, false, false, true)));
    }

    /**
     * On a public repo git is never challenged, so it never sends credentials and a member would
     * silently receive the smaller anonymous view. The /a/ path always challenges.
     */
    @Test
    void membersOfAPublicRepoGetTheirOwnViewFromTheAuthenticatedPath() throws Exception {
        repoService.setVisibility("admin", "app", "PUBLIC", List.of(), List.of("TOPSECRET.txt"));
        try {
            // Anonymous: the public view, minus the excluded file.
            try (Git clone = Git.cloneRepository().setURI(cloneUrl())
                    .setDirectory(freshCloneDir().toFile()).call()) {
                Repository db = clone.getRepository();
                assertEquals(Set.of("frontend/app.js", "backend/db.js", "README.md"),
                        TestGit.paths(db, db.resolve("HEAD")));
            }

            // admin over /a/: challenged, so credentials are sent, so the full view arrives.
            try (Git clone = Git.cloneRepository()
                    .setURI("http://localhost:" + port + "/a/app.git")
                    .setDirectory(freshCloneDir().toFile())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("admin", "admin123"))
                    .call()) {
                Repository db = clone.getRepository();
                assertEquals(Set.of("frontend/app.js", "backend/db.js", "README.md", "TOPSECRET.txt"),
                        TestGit.paths(db, db.resolve("HEAD")), "the superadmin sees everything");
            }

            // …and that path refuses anonymous access even though the repo is public.
            Path target = freshCloneDir();
            assertThrows(TransportException.class, () -> Git.cloneRepository()
                    .setURI("http://localhost:" + port + "/a/app.git")
                    .setDirectory(target.toFile()).call());
        } finally {
            repoService.setVisibility("admin", "app", "PRIVATE", List.of(), List.of());
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

    /**
     * Wipes the scratch storage. Uses JGit's deleter because git marks loose objects read-only and
     * Windows won't delete read-only files — a partial wipe leaves stale clone directories and
     * view repositories behind, which fails the next run in a thoroughly confusing way.
     */
    private static void deleteRecursively(Path root) throws IOException {
        FileUtils.delete(root.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
    }
}
