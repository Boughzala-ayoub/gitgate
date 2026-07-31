package dev.gitgate.acl;

import dev.gitgate.store.AppUser;
import dev.gitgate.store.AppUserRepository;
import dev.gitgate.store.Membership;
import dev.gitgate.store.MembershipRepository;
import dev.gitgate.store.MembershipRole;
import dev.gitgate.store.MembershipScope;
import dev.gitgate.store.MembershipScopeRepository;
import dev.gitgate.store.PublicScope;
import dev.gitgate.store.PublicScopeRepository;
import dev.gitgate.store.Repo;
import dev.gitgate.store.RepoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Access resolution against the seeded {@code acl.yml} fixture: admin (superadmin) owns
 * {@code app} and {@code web}; alice is scoped to {@code frontend} + read-only {@code *.md}
 * on app, but read-everything on web; bob owns neither and is scoped to {@code backend}.
 */
@SpringBootTest
class AclServiceTest {

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:acl-test;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AclService acl;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private RepoRepository repos;
    @Autowired
    private PublicScopeRepository publicScopes;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private MembershipScopeRepository scopes;

    @Test
    void collaboratorGetsOnlyTheirScopedPaths() {
        ReadView view = acl.resolve("alice", "app");

        assertTrue(view.matcher().isReadable("frontend/app.js"));
        assertTrue(view.matcher().isReadable("README.md"));
        assertFalse(view.matcher().isReadable("backend/db.js"));
        assertFalse(view.matcher().isReadable("TOPSECRET.txt"));

        assertTrue(view.writeMatcher().isReadable("frontend/app.js"));
        assertFalse(view.writeMatcher().isReadable("README.md"), "*.md is read-only for alice");
    }

    @Test
    void theSameUserGetsDifferentAccessOnDifferentRepos() {
        ReadView onApp = acl.resolve("alice", "app");
        ReadView onWeb = acl.resolve("alice", "web");

        assertFalse(onApp.matcher().isReadable("backend/db.js"));
        assertTrue(onWeb.matcher().isReadable("backend/db.js"), "alice reads all of web");
        assertFalse(onWeb.writeMatcher().isReadable("backend/db.js"), "but writes none of it");
    }

    @Test
    void superAdminSeesEverythingAndNonMembersSeeNothing() {
        ReadView admin = acl.resolve("admin", "app");
        assertTrue(admin.matcher().isReadable("TOPSECRET.txt"));
        assertTrue(admin.writeMatcher().isReadable("TOPSECRET.txt"));

        ensureUser("stranger");
        assertTrue(acl.resolve("stranger", "app").isEmpty());
        assertTrue(acl.resolve("nobody-at-all", "app").isEmpty(), "unknown users get nothing");
        assertTrue(acl.resolve("bob", "web").isEmpty(), "bob is not a member of web");
    }

    @Test
    void anonymousUsersGetNothingFromAPrivateRepo() {
        assertTrue(acl.resolve(null, "app").isEmpty());
        assertFalse(acl.isPublicRepo("app"));
    }

    @Test
    void partiallyPublicRepoExposesOnlyItsPublicPathsAndNeverWrites() {
        AppUser admin = users.findByUsername("admin").orElseThrow();
        Repo repo = repos.save(new Repo("brochure", admin));
        repo.setVisibility("PUBLIC");
        repos.save(repo);
        publicScopes.save(new PublicScope(repo, "docs/**"));

        assertTrue(acl.isPublicRepo("brochure"));
        ensureUser("stranger");

        for (String viewer : List.of("anonymous", "stranger")) {
            ReadView view = acl.resolve("anonymous".equals(viewer) ? null : viewer, "brochure");
            assertTrue(view.matcher().isReadable("docs/guide.md"), viewer);
            assertFalse(view.matcher().isReadable("src/secret.js"), viewer);
            assertFalse(view.writeMatcher().isReadable("docs/guide.md"), viewer + " must be read-only");
        }
    }

    @Test
    void aPublicRepoCanHideIndividualFilesFromCloners() {
        AppUser admin = users.findByUsername("admin").orElseThrow();
        Repo repo = repos.save(new Repo("openish", admin));
        repo.setVisibility("PUBLIC");
        repos.save(repo);
        // Fully public, except two carve-outs.
        publicScopes.save(new PublicScope(repo, "secrets.env", true));
        publicScopes.save(new PublicScope(repo, "internal", true));

        ReadView anon = acl.resolve(null, "openish");

        assertTrue(anon.matcher().isReadable("README.md"), "everything else stays public");
        assertTrue(anon.matcher().isReadable("src/app.js"));
        assertFalse(anon.matcher().isReadable("secrets.env"), "excluded file must not be clonable");
        assertFalse(anon.matcher().isReadable("internal/notes.md"), "excluding a folder hides its subtree");
        assertFalse(anon.writeMatcher().isReadable("README.md"));
    }

    @Test
    void aCollaboratorCanBeGrantedAFolderMinusOneFile() {
        AppUser admin = users.findByUsername("admin").orElseThrow();
        AppUser dave = ensureUser("dave");
        Repo repo = repos.save(new Repo("carved", admin));
        Membership m = memberships.save(new Membership(repo, dave, MembershipRole.WRITE));
        scopes.save(new MembershipScope(m, "backend", true, true));
        scopes.save(new MembershipScope(m, "backend/secrets.env", false, false, true));

        ReadView view = acl.resolve("dave", "carved");

        assertTrue(view.matcher().isReadable("backend/db.js"));
        assertTrue(view.writeMatcher().isReadable("backend/db.js"));
        assertFalse(view.matcher().isReadable("backend/secrets.env"), "carved out of the grant");
        assertFalse(view.writeMatcher().isReadable("backend/secrets.env"),
                "you cannot write what you cannot see");
        assertFalse(view.matcher().isReadable("frontend/app.js"), "still default-deny elsewhere");
    }

    @Test
    void viewsWithDifferentExclusionsAreNotShared() {
        // viewId keys the on-disk materialized view. If exclusions didn't feed it, a user who
        // hid a file would be served the cached view of a user who hadn't.
        AppUser admin = users.findByUsername("admin").orElseThrow();
        AppUser erin = ensureUser("erin");
        AppUser frank = ensureUser("frank");
        Repo repo = repos.save(new Repo("cachekey", admin));

        Membership a = memberships.save(new Membership(repo, erin, MembershipRole.READ));
        scopes.save(new MembershipScope(a, "src", true, false));

        Membership b = memberships.save(new Membership(repo, frank, MembershipRole.READ));
        scopes.save(new MembershipScope(b, "src", true, false));
        scopes.save(new MembershipScope(b, "src/secret.txt", false, false, true));

        assertNotEquals(acl.resolve("erin", "cachekey").viewId(), acl.resolve("frank", "cachekey").viewId(),
                "same grant, different exclusions — must be different views");
    }

    @Test
    void repoNamesResolveWithOrWithoutTheGitSuffix() {
        assertEquals(acl.resolve("alice", "app").viewId(), acl.resolve("alice", "app.git").viewId());
        assertEquals(acl.resolve("alice", "app").viewId(), acl.resolve("alice", "/app.git/").viewId());
    }

    @Test
    void usersWithIdenticalPermissionsShareAViewId() {
        assertEquals(acl.resolve("admin", "app").viewId(), acl.resolve("admin", "web").viewId());
        assertNotEquals(acl.resolve("alice", "app").viewId(), acl.resolve("bob", "app").viewId());
    }

    /** A user with no memberships anywhere — used to probe non-member access. */
    private AppUser ensureUser(String username) {
        return users.findByUsername(username).orElseGet(() -> users.save(new AppUser(username)));
    }
}
