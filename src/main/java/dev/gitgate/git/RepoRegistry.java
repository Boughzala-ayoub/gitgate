package dev.gitgate.git;

import dev.gitgate.store.AppUser;
import dev.gitgate.store.AppUserRepository;
import dev.gitgate.store.Repo;
import dev.gitgate.store.RepoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers repositories in the multi-tenant model. On the first push to a new name, the
 * pusher becomes the owner (push-to-create); existing repos are left untouched.
 */
@Service
public class RepoRegistry {

    private final RepoRepository repos;
    private final AppUserRepository users;

    public RepoRegistry(RepoRepository repos, AppUserRepository users) {
        this.repos = repos;
        this.users = users;
    }

    @Transactional
    public void ensureRegistered(String repoName, String username) {
        String name = normalize(repoName);
        if (repos.findByName(name).isPresent()) {
            return;
        }
        AppUser owner = username == null ? null : users.findByUsername(username).orElse(null);
        repos.save(new Repo(name, owner));
    }

    private String normalize(String repoName) {
        String trimmed = repoName.replaceAll("^/+", "").replaceAll("/+$", "");
        return trimmed.endsWith(".git") ? trimmed.substring(0, trimmed.length() - 4) : trimmed;
    }
}
