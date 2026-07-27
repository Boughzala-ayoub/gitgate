package dev.gitgate.api;

import java.time.Instant;
import java.util.List;

/** Request/response payloads for the admin API. */
public final class Dtos {

    private Dtos() {
    }

    public record UserDto(Long id, String username, List<String> roles, int tokenCount) {}

    public record RoleDto(Long id, String name, List<RuleDto> rules) {}

    public record RuleDto(Long id, String pathGlob, boolean canRead, boolean canWrite) {}

    public record TokenDto(Long id, String label, Instant createdAt, Instant lastUsedAt) {}

    public record AuditDto(Instant ts, String username, String repo, String action, String ref, String detail) {}

    public record CreateUserReq(String username) {}

    public record SetRolesReq(List<String> roles) {}

    public record CreateRoleReq(String name) {}

    public record CreateRuleReq(String pathGlob, boolean canRead, boolean canWrite) {}

    public record CreateTokenReq(String label) {}

    public record TokenSecretResp(String token) {}

    // ----- Phase 7: accounts & self-service -----

    public record SignupReq(String username, String password) {}

    public record MeDto(String username, boolean superadmin) {}

    public record RepoDto(String name, String owner, String visibility, String myRole, List<String> publicPaths) {}

    public record CreateRepoReq(String name, String visibility) {}

    public record SetVisibilityReq(String visibility, List<String> publicPaths) {}

    public record ScopeDto(String path, boolean read, boolean write) {}

    public record CollaboratorDto(String username, String role, List<ScopeDto> scopes) {}

    public record AddCollaboratorReq(String username, String role, List<ScopeDto> scopes) {}

    // ----- Phase 9: in-browser code browser -----

    public record BranchesDto(String defaultBranch, List<String> branches) {}

    public record TreeEntryDto(String name, String type, String path) {}

    public record BlobDto(String path, long size, boolean binary, boolean tooLarge, String content) {}
}
