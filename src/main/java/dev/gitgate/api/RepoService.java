package dev.gitgate.api;

import dev.gitgate.acl.AclService;
import dev.gitgate.api.Dtos.CollaboratorDto;
import dev.gitgate.api.Dtos.RepoDto;
import dev.gitgate.api.Dtos.ScopeDto;
import dev.gitgate.git.RepositoryService;
import dev.gitgate.git.ViewService;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * User/owner-scoped repository and collaborator management (Phase 7). Authorization is
 * per-repo: the owner, a repo {@code ADMIN} collaborator, or a super-admin may manage it.
 */
@Service
public class RepoService {

    private final RepoRepository repos;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final MembershipScopeRepository scopes;
    private final PublicScopeRepository publicScopes;
    private final RepositoryService gitRepos;
    private final ViewService views;
    private final AclService acl;

    public RepoService(RepoRepository repos, AppUserRepository users, MembershipRepository memberships,
                       MembershipScopeRepository scopes, PublicScopeRepository publicScopes,
                       RepositoryService gitRepos, ViewService views, AclService acl) {
        this.repos = repos;
        this.users = users;
        this.memberships = memberships;
        this.scopes = scopes;
        this.publicScopes = publicScopes;
        this.gitRepos = gitRepos;
        this.views = views;
        this.acl = acl;
    }

    @Transactional
    public RepoDto create(String me, String name, String visibility) {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}") || name.contains("..")) {
            throw new IllegalArgumentException("invalid repository name");
        }
        if (repos.findByName(name).isPresent()) {
            throw new IllegalArgumentException("repository already exists: " + name);
        }
        AppUser owner = user(me);
        Repo repo = new Repo(name, owner);
        if (visibility != null && !visibility.isBlank()) {
            repo.setVisibility(visibility);
        }
        repos.save(repo);
        try {
            gitRepos.open(name).close(); // initialise the empty bare repo so it's cloneable
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return toRepoDto(repo, "owner");
    }

    @Transactional(readOnly = true)
    public List<RepoDto> myRepos(String me) {
        AppUser u = user(me);
        Map<String, RepoDto> out = new LinkedHashMap<>();
        if (acl.isAdmin(me)) {
            repos.findAll().forEach(r -> out.put(r.getName(), toRepoDto(r, roleOf(r, u))));
            return new ArrayList<>(out.values());
        }
        repos.findByOwner(u).forEach(r -> out.put(r.getName(), toRepoDto(r, "owner")));
        memberships.findByUser(u).forEach(m ->
                out.putIfAbsent(m.getRepo().getName(), toRepoDto(m.getRepo(), m.getRole().name().toLowerCase())));
        return new ArrayList<>(out.values());
    }

    /** Public discovery: public repos whose name or owner contains the query (empty → all public). */
    @Transactional(readOnly = true)
    public List<RepoDto> explore(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        return repos.findByVisibilityOrderByNameAsc("PUBLIC").stream()
                .filter(r -> needle.isEmpty()
                        || r.getName().toLowerCase().contains(needle)
                        || (r.getOwner() != null && r.getOwner().getUsername().toLowerCase().contains(needle)))
                .limit(50)
                .map(r -> toRepoDto(r, "public"))
                .toList();
    }

    @Transactional(readOnly = true)
    public RepoDto get(String me, String name) {
        Repo repo = repoOr404(name);
        AppUser u = user(me);
        String role = roleOf(repo, u);
        if (role == null) {
            throw new ForbiddenException("no access to repository: " + name);
        }
        return toRepoDto(repo, role);
    }

    @Transactional
    public void delete(String me, String name) {
        Repo repo = repoOr404(name);
        requireOwner(me, repo);
        memberships.findByRepo(repo).forEach(this::deleteMembership);
        // Public scopes reference the repo with a non-optional FK: leaving them behind makes the
        // repo row undeletable, so deleting a partially-public repo fails outright.
        publicScopes.findByRepo(repo).forEach(publicScopes::delete);
        repos.delete(repo);
        try {
            gitRepos.delete(name);
            views.deleteViews(name); // views are a cache, but a same-named repo must not inherit them
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional(readOnly = true)
    public List<CollaboratorDto> collaborators(String me, String name) {
        Repo repo = repoOr404(name);
        requireRepoAdmin(me, repo);
        return memberships.findByRepo(repo).stream().map(m ->
                new CollaboratorDto(m.getUser().getUsername(), m.getRole().name(),
                        scopes.findByMembership(m).stream()
                                .map(s -> new ScopeDto(s.getPathGlob(), s.isCanRead(), s.isCanWrite()))
                                .toList())).toList();
    }

    @Transactional
    public CollaboratorDto addCollaborator(String me, String name, String username, String role,
                                           List<ScopeDto> scopeReqs) {
        Repo repo = repoOr404(name);
        requireRepoAdmin(me, repo);
        AppUser collaborator = users.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("no such user: " + username));
        if (repo.getOwner() != null && repo.getOwner().getId().equals(collaborator.getId())) {
            throw new IllegalArgumentException("the owner already has full access");
        }
        // Upsert: drop any existing membership, then recreate with the requested role + scopes.
        memberships.findByRepoAndUser(repo, collaborator).ifPresent(this::deleteMembership);
        Membership membership = memberships.save(new Membership(repo, collaborator, parseRole(role)));
        List<ScopeDto> saved = new ArrayList<>();
        if (scopeReqs != null) {
            for (ScopeDto s : scopeReqs) {
                if (s.path() == null || s.path().isBlank()) {
                    continue;
                }
                scopes.save(new MembershipScope(membership, s.path(), s.read(), s.write()));
                saved.add(s);
            }
        }
        return new CollaboratorDto(username, membership.getRole().name(), saved);
    }

    @Transactional
    public RepoDto setVisibility(String me, String name, String visibility, List<String> publicPaths) {
        Repo repo = repoOr404(name);
        requireRepoAdmin(me, repo);
        String v = visibility == null ? "" : visibility.toUpperCase();
        if (!v.equals("PUBLIC") && !v.equals("PRIVATE")) {
            throw new IllegalArgumentException("visibility must be PUBLIC or PRIVATE");
        }
        repo.setVisibility(v);
        repos.save(repo);

        publicScopes.findByRepo(repo).forEach(publicScopes::delete);
        if (v.equals("PUBLIC") && publicPaths != null) {
            publicPaths.stream().filter(p -> p != null && !p.isBlank())
                    .forEach(p -> publicScopes.save(new PublicScope(repo, p.trim())));
        }
        return toRepoDto(repo, roleOf(repo, user(me)));
    }

    @Transactional
    public void removeCollaborator(String me, String name, String username) {
        Repo repo = repoOr404(name);
        requireRepoAdmin(me, repo);
        AppUser collaborator = users.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + username));
        memberships.findByRepoAndUser(repo, collaborator).ifPresent(this::deleteMembership);
    }

    // ----- helpers -----

    private void deleteMembership(Membership m) {
        scopes.findByMembership(m).forEach(scopes::delete);
        memberships.delete(m);
    }

    private AppUser user(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + username));
    }

    private Repo repoOr404(String name) {
        return repos.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("repository not found: " + name));
    }

    /** The caller's role on a repo, or null if they have none. */
    private String roleOf(Repo repo, AppUser u) {
        if (acl.isAdmin(u.getUsername())) {
            return "admin";
        }
        if (repo.getOwner() != null && repo.getOwner().getId().equals(u.getId())) {
            return "owner";
        }
        return memberships.findByRepoAndUser(repo, u)
                .map(m -> m.getRole().name().toLowerCase())
                .orElse("PUBLIC".equals(repo.getVisibility()) ? "public" : null);
    }

    private void requireRepoAdmin(String me, Repo repo) {
        if (acl.isAdmin(me)) {
            return;
        }
        AppUser u = user(me);
        boolean owner = repo.getOwner() != null && repo.getOwner().getId().equals(u.getId());
        boolean repoAdmin = memberships.findByRepoAndUser(repo, u)
                .map(m -> m.getRole() == MembershipRole.ADMIN).orElse(false);
        if (!owner && !repoAdmin) {
            throw new ForbiddenException("you must be an admin of '" + repo.getName() + "'");
        }
    }

    private void requireOwner(String me, Repo repo) {
        if (acl.isAdmin(me)) {
            return;
        }
        AppUser u = user(me);
        if (repo.getOwner() == null || !repo.getOwner().getId().equals(u.getId())) {
            throw new ForbiddenException("only the owner can do that");
        }
    }

    private MembershipRole parseRole(String role) {
        if (role == null) {
            return MembershipRole.WRITE;
        }
        try {
            return MembershipRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid role: " + role + " (READ|WRITE|ADMIN)");
        }
    }

    private RepoDto toRepoDto(Repo r, String myRole) {
        String owner = r.getOwner() == null ? null : r.getOwner().getUsername();
        List<String> publicPaths = "PUBLIC".equals(r.getVisibility())
                ? publicScopes.findByRepo(r).stream().map(PublicScope::getPathGlob).sorted().toList()
                : List.of();
        return new RepoDto(r.getName(), owner, r.getVisibility(), myRole, publicPaths);
    }
}
