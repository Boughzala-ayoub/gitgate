package dev.gitgate.auth;

import dev.gitgate.acl.AclService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * HTTP Basic auth over (username, personal-access-token). Path tiers:
 * <ul>
 *   <li><b>git</b> endpoints — any authenticated user (their per-repo view does the filtering)</li>
 *   <li><b>/api/auth/**</b> — public (signup/login)</li>
 *   <li><b>/api/admin/**</b> — authenticated <em>and</em> super-admin</li>
 *   <li>other <b>/api/**</b> — authenticated (per-resource authorization is done in the services)</li>
 * </ul>
 * Everything else (the static dashboard) is public; it authenticates via the API itself.
 * The authenticated username is stashed as a request attribute for downstream handlers.
 */
@Component
@Order(1)
public class BasicAuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "gitgate.user";

    private final AuthService authService;
    private final AclService aclService;
    private final AnonymousRateLimiter rateLimiter;

    public BasicAuthFilter(AuthService authService, AclService aclService, AnonymousRateLimiter rateLimiter) {
        this.authService = authService;
        this.aclService = aclService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        boolean gitPath = isGitPath(uri);
        boolean apiPath = uri.startsWith("/api/");
        if (!gitPath && !apiPath) {
            chain.doFilter(request, response);
            return;
        }

        // Signup/login are public.
        if (uri.startsWith("/api/auth/")) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            // No credentials: permit anonymous READ of a public repo and anonymous browsing
            // (the browse service enforces access); everything else needs auth.
            boolean anonGitRead = gitPath && isReadOperation(request, uri) && aclService.isPublicRepo(extractRepo(uri));
            boolean openRead = uri.startsWith("/api/browse/") || uri.startsWith("/api/explore");
            if (anonGitRead || openRead) {
                if (!rateLimiter.allow(request.getRemoteAddr())) {
                    response.sendError(429, "Too many requests");
                    return;
                }
                chain.doFilter(request, response); // anonymous — no user attribute set
                return;
            }
            challenge(response);
            return;
        }

        String[] credentials = decode(header.substring("Basic ".length()));
        if (credentials == null) {
            challenge(response);
            return;
        }

        Optional<String> user = authService.authenticate(credentials[0], credentials[1]);
        if (user.isEmpty()) {
            challenge(response);
            return;
        }

        request.setAttribute(USER_ATTRIBUTE, user.get());

        // Platform-admin endpoints require the super-admin role; other /api endpoints
        // authorize per-resource inside their services.
        if (uri.startsWith("/api/admin/") && !aclService.isAdmin(user.get())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin role required");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isGitPath(String uri) {
        return uri.endsWith("/info/refs")
                || uri.endsWith("/git-upload-pack")
                || uri.endsWith("/git-receive-pack");
    }

    /** True if this git request is a fetch (read); receive-pack (push) is a write. */
    private boolean isReadOperation(HttpServletRequest request, String uri) {
        if (uri.endsWith("/git-upload-pack")) {
            return true;
        }
        if (uri.endsWith("/git-receive-pack")) {
            return false;
        }
        if (uri.endsWith("/info/refs")) {
            return "git-upload-pack".equals(request.getParameter("service"));
        }
        return false;
    }

    /** First path segment of a git URL, e.g. {@code /app.git/info/refs} → {@code app.git}. */
    private String extractRepo(String uri) {
        String s = uri.startsWith("/") ? uri.substring(1) : uri;
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }

    private String[] decode(String base64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) {
                return null;
            }
            return new String[]{decoded.substring(0, sep), decoded.substring(sep + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void challenge(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"GitGate\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
    }
}
