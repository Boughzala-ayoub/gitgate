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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Resolves a user's access to a <em>specific repo</em> (Phase 6: per-repo, multi-tenant).
 *
 * <p>Precedence: superadmin → full; repo owner → full; collaborator → their membership's path
 * scopes (or whole-repo per role if no scopes); otherwise no access. The git engine is unchanged —
 * it just calls {@link #resolve(String, String)} with the repo it's serving.
 */
@Service
public class AclService {

    private static final String ADMIN_ROLE = "admin";

    private final AppUserRepository users;
    private final RepoRepository repos;
    private final MembershipRepository memberships;
    private final MembershipScopeRepository scopes;
    private final PublicScopeRepository publicScopes;

    public AclService(AppUserRepository users, RepoRepository repos, MembershipRepository memberships,
                      MembershipScopeRepository scopes, PublicScopeRepository publicScopes) {
        this.users = users;
        this.repos = repos;
        this.memberships = memberships;
        this.scopes = scopes;
        this.publicScopes = publicScopes;
    }

    @Transactional(readOnly = true)
    public ReadView resolve(String username, String repoName) {
        Repo repo = repos.findByName(normalize(repoName)).orElse(null);

        // Anonymous: read-only access to public repos only.
        if (username == null) {
            return isPublic(repo) ? publicView(repo) : emptyView();
        }

        AppUser user = users.findByUsername(username).orElse(null);
        if (user == null) {
            return emptyView();
        }
        if (isSuperAdmin(user)) {
            return fullView();
        }
        if (repo == null) {
            return emptyView();
        }
        if (repo.getOwner() != null && repo.getOwner().getId().equals(user.getId())) {
            return fullView();
        }

        Membership membership = memberships.findByRepoAndUser(repo, user).orElse(null);
        if (membership != null) {
            return fromMembership(membership);
        }
        // Non-member: still gets the public read view if the repo is public.
        return isPublic(repo) ? publicView(repo) : emptyView();
    }

    private boolean isPublic(Repo repo) {
        return repo != null && "PUBLIC".equals(repo.getVisibility());
    }

    /**
     * Read-only view of a public repo: its public path globs (or {@code **} if none are set),
     * minus anything explicitly excluded. "Public except these files" is the common case — a repo
     * anyone may clone, with a few paths that never leave the server.
     */
    private ReadView publicView(Repo repo) {
        List<PublicScope> all = publicScopes.findByRepo(repo);
        List<String> globs = all.stream().filter(s -> !s.isExcluded())
                .map(PublicScope::getPathGlob).distinct().sorted().toList();
        List<String> exclusions = all.stream().filter(PublicScope::isExcluded)
                .map(PublicScope::getPathGlob).distinct().sorted().toList();
        if (globs.isEmpty()) {
            globs = List.of("**");
        }
        return view(globs, List.of(), exclusions); // anonymous is never writable
    }

    /** Whether a repo exists and is public — used by the auth filter to permit anonymous reads. */
    @Transactional(readOnly = true)
    public boolean isPublicRepo(String repoName) {
        return repos.findByName(normalize(repoName)).map(this::isPublic).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(String username) {
        return username != null && users.findByUsername(username).map(this::isSuperAdmin).orElse(false);
    }

    private ReadView fromMembership(Membership membership) {
        List<MembershipScope> ruleSet = scopes.findByMembership(membership);
        List<String> exclusions = ruleSet.stream().filter(MembershipScope::isExcluded)
                .map(MembershipScope::getPathGlob).distinct().sorted().toList();
        List<MembershipScope> grants = ruleSet.stream().filter(s -> !s.isExcluded()).toList();

        if (grants.isEmpty()) {
            // No grants: the role covers the whole repo, minus anything excluded from it.
            boolean canWrite = membership.getRole() != MembershipRole.READ;
            return view(List.of("**"), canWrite ? List.of("**") : List.of(), exclusions);
        }
        List<String> read = grants.stream()
                .filter(MembershipScope::isCanRead).map(MembershipScope::getPathGlob).distinct().sorted().toList();
        List<String> write = grants.stream()
                .filter(MembershipScope::isCanWrite).map(MembershipScope::getPathGlob).distinct().sorted().toList();
        return view(read, write, exclusions);
    }

    private boolean isSuperAdmin(AppUser user) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals(ADMIN_ROLE));
    }

    private ReadView fullView() {
        return view(List.of("**"), List.of("**"), List.of());
    }

    private ReadView emptyView() {
        return view(List.of(), List.of(), List.of());
    }

    /** Exclusions apply to writes as well: you cannot push a path you are not allowed to see. */
    private ReadView view(List<String> read, List<String> write, List<String> exclusions) {
        return new ReadView(read, exclusions, viewId(read, exclusions),
                GlobMatcher.of(read, exclusions), GlobMatcher.of(write, exclusions));
    }

    /** Strips a trailing {@code .git} so URL names and stored repo names line up. */
    private String normalize(String repoName) {
        if (repoName == null) {
            return "";
        }
        String trimmed = repoName.replaceAll("^/+", "").replaceAll("/+$", "");
        return trimmed.endsWith(".git") ? trimmed.substring(0, trimmed.length() - 4) : trimmed;
    }

    /**
     * Stable id for a read-permission set: first 16 hex chars of SHA-1 over the sorted globs and
     * exclusions. Views are cached on disk under this id and shared by everyone who resolves to
     * it, so <em>both</em> halves must contribute — otherwise a user who excludes a file would be
     * served the view of someone who didn't.
     */
    private String viewId(List<String> globs, List<String> exclusions) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String key = String.join("\n", globs) + "\n!\n" + String.join("\n", exclusions);
            byte[] digest = sha1.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
