package dev.gitgate.store;

import dev.gitgate.auth.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On first run (empty database), seeds users, an {@code admin} superadmin role, repos, and
 * per-repo memberships with path scopes from {@code acl.yml}. Seeded tokens equal
 * {@code <username>123} so the existing demos and scripts keep working.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final TokenRepository tokens;
    private final RepoRepository repos;
    private final MembershipRepository memberships;
    private final MembershipScopeRepository scopes;
    private final String aclFile;
    private final String adminToken;

    public DataSeeder(AppUserRepository users, RoleRepository roles, TokenRepository tokens,
                      RepoRepository repos, MembershipRepository memberships,
                      MembershipScopeRepository scopes, @Value("${gitgate.acl-file:}") String aclFile,
                      @Value("${gitgate.admin-token:}") String adminToken) {
        this.users = users;
        this.roles = roles;
        this.tokens = tokens;
        this.repos = repos;
        this.memberships = memberships;
        this.scopes = scopes;
        this.aclFile = aclFile;
        this.adminToken = adminToken;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public void run(ApplicationArguments args) throws IOException {
        if (users.count() > 0) {
            return;
        }

        Map<String, Object> root;
        try (InputStream in = openConfig()) {
            root = new Yaml().load(in);
        }

        Role adminRole = roles.save(new Role("admin"));

        Map<String, AppUser> userByName = new HashMap<>();
        for (Map<String, Object> node : (List<Map<String, Object>>) root.getOrDefault("users", List.of())) {
            String username = (String) node.get("username");
            boolean superadmin = Boolean.TRUE.equals(node.get("superadmin"));
            AppUser user = new AppUser(username);
            if (superadmin) {
                user.setRoles(Set.of(adminRole));
            }
            user = users.save(user);
            userByName.put(username, user);
            tokens.save(new Token(user, Tokens.sha256Hex(tokenFor(username, superadmin)), "seeded", Instant.now()));
        }

        for (Map<String, Object> repoNode : (List<Map<String, Object>>) root.getOrDefault("repos", List.of())) {
            String name = (String) repoNode.get("name");
            AppUser owner = userByName.get((String) repoNode.get("owner"));
            Repo repo = repos.save(new Repo(name, owner));

            List<Map<String, Object>> collaborators =
                    (List<Map<String, Object>>) repoNode.getOrDefault("collaborators", List.of());
            for (Map<String, Object> collab : collaborators) {
                AppUser user = userByName.get((String) collab.get("user"));
                if (user == null) {
                    continue;
                }
                Membership membership = memberships.save(new Membership(repo, user, MembershipRole.WRITE));
                for (Map<String, Object> scope : (List<Map<String, Object>>) collab.getOrDefault("scopes", List.of())) {
                    scopes.save(new MembershipScope(membership, (String) scope.get("path"),
                            Boolean.TRUE.equals(scope.get("read")), Boolean.TRUE.equals(scope.get("write"))));
                }
            }
        }

        log.info("Seeded {} users and {} repos from acl.yml", userByName.size(), repos.count());
    }

    /**
     * The seed token for a user. Demo accounts get the documented {@code <username>123}; the
     * superadmin gets {@code gitgate.admin-token} when set, because otherwise every deployment
     * that skips reading the README ships with a publicly known admin password.
     */
    private String tokenFor(String username, boolean superadmin) {
        if (superadmin && adminToken != null && !adminToken.isBlank()) {
            log.info("Seeded superadmin '{}' with the configured admin token", username);
            return adminToken;
        }
        if (superadmin) {
            log.warn("!! Superadmin '{}' seeded with the DEMO token '{}123'. Set GITGATE_ADMIN_TOKEN "
                    + "(or gitgate.admin-token) before exposing this server.", username, username);
        }
        return username + "123";
    }

    private InputStream openConfig() throws IOException {
        if (aclFile != null && !aclFile.isBlank() && Files.exists(Path.of(aclFile))) {
            return Files.newInputStream(Path.of(aclFile));
        }
        InputStream in = getClass().getResourceAsStream("/acl.yml");
        if (in == null) {
            throw new IOException("acl.yml not found on classpath");
        }
        return in;
    }
}
