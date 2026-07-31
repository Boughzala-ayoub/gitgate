package dev.gitgate.git;

import dev.gitgate.TestGit;
import dev.gitgate.acl.GlobMatcher;
import dev.gitgate.acl.ReadView;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write path: a push made against a filtered view, merged back onto canonical history.
 * These are the guarantees the whole design exists for.
 */
class PushReconcilerTest {

    private final ViewMaterializer materializer = new ViewMaterializer();
    private final PushReconciler reconciler = new PushReconciler(materializer);

    /** alice: sees frontend/ and *.md, may only write frontend/. */
    private static ReadView alice() {
        return access(List.of("frontend", "*.md"), List.of("frontend"));
    }

    private static ReadView access(List<String> read, List<String> write) {
        return access(read, write, List.of());
    }

    private static ReadView access(List<String> read, List<String> write, List<String> exclusions) {
        return new ReadView(read, exclusions, "test",
                GlobMatcher.of(read, exclusions), GlobMatcher.of(write, exclusions));
    }

    @Test
    void pushKeepsHiddenPathsIntact(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.linkAlternate(view, canonical);
            ObjectId viewBase = materializeFor(canonical, view, alice());

            ObjectId pushed = TestGit.commit(view, "alice: tweak ui", Map.of(
                    "frontend/app.js", "console.log('ui v2');\n",
                    "README.md", "# app\n"));

            ReceiveCommand command = push(viewBase, pushed);
            reconciler.handle(canonical, view, alice(), List.of(command));

            assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.getResult(), "push should be accepted");

            ObjectId tip = TestGit.head(canonical);
            assertEquals(Set.of("frontend/app.js", "backend/db.js", "README.md", "TOPSECRET.txt"),
                    TestGit.paths(canonical, tip));
            assertEquals("console.log('ui v2');\n", TestGit.content(canonical, tip, "frontend/app.js"));
            assertEquals("connect('postgres');\n", TestGit.content(canonical, tip, "backend/db.js"));
            assertEquals("launch codes\n", TestGit.content(canonical, tip, "TOPSECRET.txt"));
        }
    }

    @Test
    void writingAReadOnlyPathIsRejected(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.linkAlternate(view, canonical);
            ObjectId viewBase = materializeFor(canonical, view, alice());
            ObjectId canonicalBefore = TestGit.head(canonical);

            // alice may read *.md but not write it.
            ObjectId pushed = TestGit.commit(view, "alice: edit readme", Map.of(
                    "frontend/app.js", "console.log('ui');\n",
                    "README.md", "# hijacked\n"));

            ReceiveCommand command = push(viewBase, pushed);
            reconciler.handle(canonical, view, alice(), List.of(command));

            assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.getResult());
            assertTrue(command.getMessage().contains("README.md"), command.getMessage());
            assertEquals(canonicalBefore, TestGit.head(canonical), "canonical must be untouched");
        }
    }

    @Test
    void injectingIntoAHiddenPathIsRejected(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.linkAlternate(view, canonical);
            ObjectId viewBase = materializeFor(canonical, view, alice());
            ObjectId canonicalBefore = TestGit.head(canonical);

            // alice can't see backend/ at all — smuggling a file in must not work.
            ObjectId pushed = TestGit.commit(view, "alice: oops", Map.of(
                    "frontend/app.js", "console.log('ui');\n",
                    "README.md", "# app\n",
                    "backend/evil.js", "steal();\n"));

            ReceiveCommand command = push(viewBase, pushed);
            reconciler.handle(canonical, view, alice(), List.of(command));

            assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.getResult());
            assertTrue(command.getMessage().contains("backend/evil.js"), command.getMessage());
            assertEquals(canonicalBefore, TestGit.head(canonical));
        }
    }

    @Test
    void deletionsAreScopedToWhatTheUserCanSee(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.linkAlternate(view, canonical);

            // A user scoped to frontend/ only, with write on it.
            ReadView scoped = access(List.of("frontend"), List.of("frontend"));
            ObjectId viewBase = materializeFor(canonical, view, scoped);

            // Their whole world is frontend/app.js — deleting it empties their view entirely.
            ObjectId pushed = TestGit.commit(view, "drop the app", Map.of("frontend/keep.js", "kept\n"));

            ReceiveCommand command = push(viewBase, pushed);
            reconciler.handle(canonical, view, scoped, List.of(command));

            assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.getResult());
            assertEquals(Set.of("frontend/keep.js", "backend/db.js", "README.md", "TOPSECRET.txt"),
                    TestGit.paths(canonical, TestGit.head(canonical)),
                    "the deletion applies to their paths only; everything hidden survives");
        }
    }

    @Test
    void branchDeletionIsRejected(@TempDir Path tmp) throws Exception {
        try (Repository canonical = TestGit.bare(tmp.resolve("canonical.git"));
             Repository view = TestGit.bare(tmp.resolve("view.git"))) {

            TestGit.commit(canonical, "initial", TestGit.demoFiles());
            TestGit.linkAlternate(view, canonical);
            ObjectId viewBase = materializeFor(canonical, view, alice());

            ReceiveCommand command = new ReceiveCommand(viewBase, ObjectId.zeroId(), TestGit.BRANCH);
            reconciler.handle(canonical, view, alice(), List.of(command));

            assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.getResult());
            assertNotNull(TestGit.head(canonical));
        }
    }

    private ObjectId materializeFor(Repository canonical, Repository view, ReadView access) throws IOException {
        materializer.materialize(canonical, view, access.matcher());
        return TestGit.head(view);
    }

    private ReceiveCommand push(ObjectId from, ObjectId to) {
        return new ReceiveCommand(from, to, TestGit.BRANCH);
    }
}
