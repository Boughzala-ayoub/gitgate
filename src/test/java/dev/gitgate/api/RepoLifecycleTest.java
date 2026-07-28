package dev.gitgate.api;

import dev.gitgate.TestGit;
import dev.gitgate.git.RepositoryService;
import dev.gitgate.git.ViewService;
import dev.gitgate.store.PublicScopeRepository;
import dev.gitgate.store.RepoRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a repository must leave nothing behind — no database rows pointing at a row that's
 * gone, and no directories on disk that a later repo of the same name would inherit.
 */
@SpringBootTest
class RepoLifecycleTest {

    private static final Path DATA = Path.of("target", "test-data", "repo-lifecycle");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:repo-lifecycle;DB_CLOSE_DELAY=-1");
        registry.add("gitgate.repo-root", () -> DATA.resolve("repos").toString());
        registry.add("gitgate.views-root", () -> DATA.resolve("views").toString());
    }

    @Autowired
    private RepoService repoService;
    @Autowired
    private RepoRepository repos;
    @Autowired
    private PublicScopeRepository publicScopes;
    @Autowired
    private RepositoryService gitRepos;
    @Autowired
    private ViewService views;

    @Test
    void deletingAPartiallyPublicRepoDoesNotStrandItsPublicScopes() {
        repoService.create("admin", "brochure", "PRIVATE");
        repoService.setVisibility("admin", "brochure", "PUBLIC", List.of("docs/**", "*.md"));
        assertFalse(publicScopes.findByRepo(repos.findByName("brochure").orElseThrow()).isEmpty(),
                "fixture should have public scopes to strand");

        repoService.delete("admin", "brochure");

        assertTrue(repos.findByName("brochure").isEmpty(), "the repo row should be gone");
        assertEquals(0, publicScopes.count(), "no public_scope row may outlive its repo");
    }

    @Test
    void changingAnExistingCollaboratorsAccessReplacesItInsteadOfFailing() {
        repoService.create("admin", "shared", "PRIVATE");
        repoService.addCollaborator("admin", "shared", "alice", "READ",
                List.of(new Dtos.ScopeDto("docs", true, false)));

        // Re-saving the same person is an update, not a second membership. Deleting and
        // re-inserting the row here trips the unique (repo, user) index and 500s.
        Dtos.CollaboratorDto updated = repoService.addCollaborator("admin", "shared", "alice", "WRITE",
                List.of(new Dtos.ScopeDto("frontend", true, true)));

        assertEquals("WRITE", updated.role());
        List<Dtos.CollaboratorDto> people = repoService.collaborators("admin", "shared");
        assertEquals(1, people.size(), "the collaborator must not be duplicated");
        assertEquals(List.of("frontend"), people.get(0).scopes().stream().map(Dtos.ScopeDto::path).toList(),
                "old scopes are replaced, not accumulated");
        assertTrue(people.get(0).scopes().get(0).write(), "read+write must survive the round trip");
    }

    @Test
    void deletingARepoRemovesItsCanonicalStoreAndMaterializedViews() throws Exception {
        repoService.create("admin", "throwaway", "PRIVATE");
        try (Repository canonical = gitRepos.open("throwaway")) {
            TestGit.commit(canonical, "initial", TestGit.demoFiles());
        }
        // Materialize a view so there is something under views/ to leave behind.
        views.openView("throwaway", "admin").close();

        Path canonicalDir = DATA.resolve("repos").resolve("throwaway.git");
        Path viewsDir = DATA.resolve("views").resolve("throwaway");
        assertTrue(Files.exists(canonicalDir), "fixture: canonical repo on disk");
        assertTrue(Files.exists(viewsDir), "fixture: materialized view on disk");

        repoService.delete("admin", "throwaway");

        assertFalse(Files.exists(canonicalDir), "canonical repo directory must be removed");
        assertFalse(Files.exists(viewsDir), "materialized views must not outlive the repo");
    }
}
