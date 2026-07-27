package dev.gitgate.git;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Opens (and lazily creates) the bare canonical repositories on disk.
 *
 * <p>In Phase 0 this is the repository the client talks to directly. From Phase 1
 * on, this remains the full-content source of truth, and the smart-HTTP endpoint
 * serves a per-user <em>filtered view</em> derived from it instead.
 */
@Service
public class RepositoryService {

    private final Path baseDir;

    public RepositoryService(@Value("${gitgate.repo-root:./data/repos}") String repoRoot) {
        this.baseDir = Paths.get(repoRoot).toAbsolutePath().normalize();
    }

    /**
     * Opens the named bare repository, creating it on first access ("push to create").
     *
     * @param name repository name from the URL, with or without a trailing {@code .git}
     * @return an open {@link Repository}; the caller is responsible for closing it
     */
    public Repository open(String name) throws IOException {
        Path gitDir = baseDir.resolve(sanitize(name));

        // Guard against path traversal escaping the repo root.
        if (!gitDir.normalize().startsWith(baseDir)) {
            throw new IOException("Invalid repository name: " + name);
        }

        Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setBare()
                .build();

        if (!repo.getObjectDatabase().exists()) {
            repo.create(true); // initialise a bare repo
        }
        return repo;
    }

    /** Deletes the bare canonical repository directory for {@code name} (best-effort). */
    public void delete(String name) throws IOException {
        java.nio.file.Path gitDir = baseDir.resolve(sanitize(name));
        if (!gitDir.normalize().startsWith(baseDir) || !java.nio.file.Files.exists(gitDir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(gitDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    /** Lists the names (without the {@code .git} suffix) of existing canonical repositories. */
    public java.util.List<String> listRepoNames() {
        java.io.File dir = baseDir.toFile();
        java.io.File[] children = dir.listFiles(java.io.File::isDirectory);
        if (children == null) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(children)
                .map(f -> f.getName().endsWith(".git")
                        ? f.getName().substring(0, f.getName().length() - 4)
                        : f.getName())
                .sorted()
                .toList();
    }

    /** Normalises a URL repo name to a safe {@code <name>.git} directory name. */
    private String sanitize(String name) {
        String trimmed = name.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.isEmpty() || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid repository name: " + name);
        }
        return trimmed.endsWith(".git") ? trimmed : trimmed + ".git";
    }
}
