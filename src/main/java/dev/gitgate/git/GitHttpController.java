package dev.gitgate.git;

import dev.gitgate.acl.AclService;
import dev.gitgate.acl.ReadView;
import dev.gitgate.audit.AuditService;
import dev.gitgate.auth.BasicAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Implements the Git "smart HTTP" protocol directly on top of JGit's
 * {@link UploadPack} / {@link ReceivePack} in stateless-RPC mode.
 *
 * <p>Two phases per operation:
 * <ol>
 *   <li>{@code GET /{repo}/info/refs?service=git-upload-pack} — ref advertisement</li>
 *   <li>{@code POST /{repo}/git-upload-pack} — the actual fetch negotiation + packfile</li>
 * </ol>
 * (and the {@code git-receive-pack} equivalents for push).
 *
 * <p>Doing this by hand instead of using JGit's {@code GitServlet} avoids a
 * javax/jakarta servlet-API clash with Spring Boot 3, and — more importantly —
 * gives us the single choke point where Phase 1 will swap the canonical repo for
 * a per-user filtered view.
 */
@RestController
public class GitHttpController {

    private static final Logger log = LoggerFactory.getLogger(GitHttpController.class);

    private static final String UPLOAD_PACK = "git-upload-pack";
    private static final String RECEIVE_PACK = "git-receive-pack";

    private final RepositoryService repositoryService;
    private final ViewService viewService;
    private final AclService aclService;
    private final PushReconciler pushReconciler;
    private final AuditService auditService;
    private final RepoRegistry repoRegistry;

    public GitHttpController(RepositoryService repositoryService, ViewService viewService,
                             AclService aclService, PushReconciler pushReconciler,
                             AuditService auditService, RepoRegistry repoRegistry) {
        this.repositoryService = repositoryService;
        this.viewService = viewService;
        this.aclService = aclService;
        this.pushReconciler = pushReconciler;
        this.auditService = auditService;
        this.repoRegistry = repoRegistry;
    }

    /** Step 1: advertise refs for either upload-pack (fetch) or receive-pack (push). */
    @GetMapping({"/{repo}/info/refs", "/a/{repo}/info/refs"})
    public void infoRefs(@PathVariable String repo,
                         @RequestParam("service") String service,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        if (!UPLOAD_PACK.equals(service) && !RECEIVE_PACK.equals(service)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Unsupported service: " + service);
            return;
        }

        noCache(response);
        response.setContentType("application/x-" + service + "-advertisement");

        // Both advertisements come from the user's filtered view: the client's local refs
        // are view-commit ids, so the remote must be described in the same terms.
        try (Repository db = viewService.openView(repo, currentUser(request))) {
            OutputStream out = response.getOutputStream();
            PacketLineOut pck = new PacketLineOut(out);
            pck.writeString("# service=" + service + "\n");
            pck.end(); // flush-pkt terminating the banner

            if (UPLOAD_PACK.equals(service)) {
                UploadPack up = new UploadPack(db);
                up.setBiDirectionalPipe(false);
                up.setExtraParameters(protocolParameters(request));
                up.sendAdvertisedRefs(new PacketLineOutRefAdvertiser(pck));
            } else {
                ReceivePack rp = new ReceivePack(db);
                rp.setBiDirectionalPipe(false);
                rp.sendAdvertisedRefs(new PacketLineOutRefAdvertiser(pck));
            }
            out.flush();
        }
    }

    /** Step 2 (fetch): negotiate wants/haves and stream the packfile back. */
    @PostMapping({"/{repo}/git-upload-pack", "/a/{repo}/git-upload-pack"})
    public void uploadPack(@PathVariable String repo,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {

        noCache(response);
        response.setContentType("application/x-git-upload-pack-result");

        String user = currentUser(request);
        try (Repository db = viewService.openView(repo, user)) {
            UploadPack up = new UploadPack(db);
            up.setBiDirectionalPipe(false);
            up.setExtraParameters(protocolParameters(request));
            up.upload(bodyStream(request), response.getOutputStream(), null);
        }
        auditService.record(user == null ? "anonymous" : user, repo, "fetch", null, null);
    }

    /** Step 2 (push): receive the client's packfile and update refs. */
    @PostMapping({"/{repo}/git-receive-pack", "/a/{repo}/git-receive-pack"})
    public void receivePack(@PathVariable String repo,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {

        noCache(response);
        response.setContentType("application/x-git-receive-pack-result");

        String user = currentUser(request);
        repoRegistry.ensureRegistered(repo, user); // push-to-create: first pusher owns the repo
        ReadView access = aclService.resolve(user, repo);

        // Receive into the user's view; the reconciler maps the push onto canonical history,
        // enforcing write-ACL and preserving hidden paths. The view ref then fast-forwards
        // so the client sees a normal success.
        try (Repository canonical = repositoryService.open(repo);
             Repository view = viewService.openView(repo, user)) {
            ReceivePack rp = new ReceivePack(view);
            rp.setBiDirectionalPipe(false);
            rp.setPreReceiveHook((receivePack, commands) ->
                    pushReconciler.handle(canonical, view, access, commands));
            rp.receive(bodyStream(request), response.getOutputStream(), null);
            fixDanglingHead(canonical);
        }
        auditService.record(user, repo, "push", null, null);
    }

    /**
     * Points HEAD at a real branch after a push.
     *
     * <p>A freshly created bare repo has {@code HEAD -> refs/heads/master}, but the
     * client may have pushed {@code main} (or anything else). If HEAD still resolves
     * to a non-existent ref, clones can't pick a default branch and check out an
     * empty tree — so we repoint HEAD, preferring main, then master, then any branch.
     */
    private void fixDanglingHead(Repository db) throws IOException {
        Ref head = db.exactRef(Constants.HEAD);
        if (head == null || !head.isSymbolic()) {
            return; // detached or missing HEAD — leave as-is
        }
        if (db.exactRef(head.getTarget().getName()) != null) {
            return; // HEAD already points at an existing branch
        }

        List<Ref> branches = db.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        if (branches.isEmpty()) {
            return;
        }
        Ref chosen = pick(branches, Constants.R_HEADS + "main")
                .or(() -> pick(branches, Constants.R_HEADS + "master"))
                .orElse(branches.get(0));

        RefUpdate update = db.updateRef(Constants.HEAD);
        update.disableRefLog();
        update.link(chosen.getName());
        log.debug("Repointed HEAD to {} for {}", chosen.getName(), db.getDirectory());
    }

    private Optional<Ref> pick(List<Ref> branches, String name) {
        return branches.stream().filter(r -> r.getName().equals(name)).findFirst();
    }

    /** The username the auth filter established for this request. */
    private String currentUser(HttpServletRequest request) {
        return (String) request.getAttribute(BasicAuthFilter.USER_ATTRIBUTE);
    }

    /** Unwraps gzip request bodies, which git uses for larger payloads. */
    private InputStream bodyStream(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    /** Passes the client's requested protocol version (e.g. "version=2") through to JGit. */
    private List<String> protocolParameters(HttpServletRequest request) {
        String header = request.getHeader("Git-Protocol");
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.asList(header.split(":"));
    }

    private void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "Fri, 01 Jan 1980 00:00:00 GMT");
    }
}
