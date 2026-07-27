package dev.gitgate.git;

import dev.gitgate.TestGit;
import dev.gitgate.acl.GlobMatcher;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read path: canonical history rewritten into a per-user filtered projection. */
class ViewMaterializerTest {

    private final ViewMaterializer materializer = new ViewMaterializer();

    @Test
    void viewContainsOnlyReadablePaths(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.linkAlternate(view, canonical);

            materializer.materialize(canonical, view, GlobMatcher.of(List.of("frontend", "*.md")));

            assertEquals(Set.of("frontend/app.js", "README.md"), TestGit.paths(view, TestGit.head(view)));
            // The blob content that *is* visible still resolves — it is shared, not copied.
            assertEquals("console.log('ui');\n", TestGit.content(view, TestGit.head(view), "frontend/app.js"));
        }
    }

    @Test
    void rewritingIsDeterministicSoFetchStaysIncremental(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository first = TestGit.bare(tmp.resolve("first.git"));
             Repository second = TestGit.bare(tmp.resolve("second.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.commit(canonical, "second", Map.of(
                    "frontend/app.js", "console.log('ui v2');\n",
                    "backend/db.js", "connect('postgres');\n",
                    "README.md", "# app\n",
                    "TOPSECRET.txt", "launch codes\n"));

            GlobMatcher matcher = GlobMatcher.of(List.of("frontend", "*.md"));
            materializer.materialize(canonical, first, matcher);
            materializer.materialize(canonical, second, matcher);

            assertEquals(TestGit.head(first), TestGit.head(second),
                    "the same view of the same history must yield the same object ids");

            // Re-materializing an existing view is a no-op, not a rewrite.
            ObjectId before = TestGit.head(first);
            materializer.materialize(canonical, first, matcher);
            assertEquals(before, TestGit.head(first));
        }
    }

    @Test
    void historyShapeAndMetadataSurviveTheRewrite(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "one", TestGit.demoFiles());
            TestGit.commit(canonical, "two", Map.of("frontend/app.js", "v2\n", "TOPSECRET.txt", "codes\n"));
            TestGit.commit(canonical, "three", Map.of("frontend/app.js", "v3\n", "TOPSECRET.txt", "codes\n"));
            TestGit.linkAlternate(view, canonical);

            materializer.materialize(canonical, view, GlobMatcher.of(List.of("frontend")));

            try (RevWalk walk = new RevWalk(view)) {
                RevCommit tip = walk.parseCommit(TestGit.head(view));
                assertEquals("three", tip.getFullMessage());
                assertEquals("Test", tip.getAuthorIdent().getName());
                assertEquals(1, tip.getParentCount());
                assertEquals(3, countAncestors(walk, tip), "every canonical commit keeps a view counterpart");
            }
        }
    }

    @Test
    void emptyViewYieldsEmptyCommitsRatherThanFailing(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());

            materializer.materialize(canonical, view, GlobMatcher.of(List.of()));

            assertNotNull(TestGit.head(view));
            assertTrue(TestGit.paths(view, TestGit.head(view)).isEmpty());
        }
    }

    /**
     * Deep history must not consume stack per commit. Run on a deliberately small stack so a
     * per-commit recursion overflows here long before it would in production.
     */
    @Test
    void deepHistoryDoesNotOverflowTheStack(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            for (int i = 0; i < 2000; i++) {
                TestGit.commit(canonical, "commit " + i, Map.of("frontend/app.js", "v\n", "secret.txt", "s\n"));
            }
            TestGit.linkAlternate(view, canonical);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(null, () -> {
                try {
                    materializer.materialize(canonical, view, GlobMatcher.of(List.of("frontend")));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "materialize-small-stack", 256 * 1024);
            worker.start();
            worker.join();

            if (failure.get() != null) {
                throw new AssertionError("materializing 2000 commits failed", failure.get());
            }
            assertEquals(Set.of("frontend/app.js"), TestGit.paths(view, TestGit.head(view)));
        }
    }

    private int countAncestors(RevWalk walk, RevCommit tip) throws Exception {
        walk.reset();
        walk.markStart(walk.parseCommit(tip));
        int count = 0;
        for (RevCommit ignored : walk) {
            count++;
        }
        return count;
    }
}
