package dev.gitgate.git;

import dev.gitgate.acl.AclService;
import dev.gitgate.acl.ReadView;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Produces the per-user filtered <em>view</em> repository that {@code git-upload-pack}
 * is served from.
 *
 * <p>Each view lives at {@code <views-root>/<repo>/<viewId>.git} as a bare repo whose
 * {@code objects/info/alternates} points back at the canonical object store — so
 * readable blobs are shared, and only rewritten trees/commits are stored per view.
 */
@Service
public class ViewService {

    private static final Logger log = LoggerFactory.getLogger(ViewService.class);

    private final RepositoryService repositoryService;
    private final AclService aclService;
    private final ViewMaterializer materializer;
    private final Path viewsRoot;

    /** Serializes materialization per (repo,view) so concurrent clones don't race on refs. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ViewService(RepositoryService repositoryService,
                       AclService aclService,
                       ViewMaterializer materializer,
                       @Value("${gitgate.views-root:./data/views}") String viewsRoot) {
        this.repositoryService = repositoryService;
        this.aclService = aclService;
        this.materializer = materializer;
        this.viewsRoot = Paths.get(viewsRoot).toAbsolutePath().normalize();
    }

    /**
     * Opens (creating/refreshing as needed) the filtered view of {@code repoName} for
     * {@code username}. The returned repository contains only what the user may read.
     * The caller owns and must close it.
     */
    public Repository openView(String repoName, String username) throws IOException {
        ReadView view = aclService.resolve(username, repoName);

        try (Repository canonical = repositoryService.open(repoName)) {
            Repository viewRepo = openOrCreateViewRepo(repoName, view.viewId(), canonical);

            String lockKey = repoName + "/" + view.viewId();
            synchronized (locks.computeIfAbsent(lockKey, k -> new Object())) {
                materializer.materialize(canonical, viewRepo, view.matcher());
            }
            log.debug("Serving view '{}' of '{}' to user '{}'", view.viewId(), repoName, username);
            return viewRepo;
        }
    }

    private Repository openOrCreateViewRepo(String repoName, String viewId, Repository canonical)
            throws IOException {
        Path gitDir = viewsRoot.resolve(sanitize(repoName)).resolve(viewId + ".git");
        if (!gitDir.normalize().startsWith(viewsRoot)) {
            throw new IOException("Invalid repository name: " + repoName);
        }

        Repository viewRepo = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setBare().build();
        if (!viewRepo.getObjectDatabase().exists()) {
            viewRepo.create(true);
        }
        linkAlternate(viewRepo, canonical);
        return viewRepo;
    }

    /**
     * Writes objects/info/alternates so the view can read canonical blobs without copying them.
     *
     * <p>Checked on every open, not just at creation: without this link the view resolves no blobs
     * at all and fetches fail with an opaque server error, so a view directory that lost it (a
     * half-finished cleanup, a restored backup) must repair itself rather than stay broken.
     */
    private void linkAlternate(Repository viewRepo, Repository canonical) throws IOException {
        Path canonicalObjects = canonical.getDirectory().toPath().resolve("objects").toAbsolutePath();
        Path altFile = viewRepo.getDirectory().toPath().resolve("objects").resolve("info").resolve("alternates");
        String expected = canonicalObjects + System.lineSeparator();
        if (Files.exists(altFile) && Files.readString(altFile, StandardCharsets.UTF_8).equals(expected)) {
            return;
        }
        Files.createDirectories(altFile.getParent());
        Files.writeString(altFile, expected, StandardCharsets.UTF_8);
    }

    private String sanitize(String name) {
        String trimmed = name.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        if (trimmed.isEmpty() || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid repository name: " + name);
        }
        return trimmed;
    }
}
