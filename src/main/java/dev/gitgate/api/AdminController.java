package dev.gitgate.api;

import dev.gitgate.api.Dtos.AuditDto;
import dev.gitgate.api.Dtos.CreateRoleReq;
import dev.gitgate.api.Dtos.CreateRuleReq;
import dev.gitgate.api.Dtos.CreateTokenReq;
import dev.gitgate.api.Dtos.CreateUserReq;
import dev.gitgate.api.Dtos.RoleDto;
import dev.gitgate.api.Dtos.SetRolesReq;
import dev.gitgate.api.Dtos.TokenDto;
import dev.gitgate.api.Dtos.TokenSecretResp;
import dev.gitgate.api.Dtos.UserDto;
import dev.gitgate.audit.AuditService;
import dev.gitgate.git.RepositoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Admin REST API (guarded to admins by {@code BasicAuthFilter}). Manages users, roles,
 * ACL rules, and tokens, and exposes the repo list and audit log.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;
    private final AuditService audit;
    private final RepositoryService repositories;

    public AdminController(AdminService admin, AuditService audit, RepositoryService repositories) {
        this.admin = admin;
        this.audit = audit;
        this.repositories = repositories;
    }

    // ----- users -----

    @GetMapping("/users")
    public List<UserDto> listUsers() {
        return admin.listUsers();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto createUser(@RequestBody CreateUserReq req) {
        return admin.createUser(req.username());
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable Long id) {
        admin.deleteUser(id);
    }

    @PutMapping("/users/{id}/roles")
    public UserDto setRoles(@PathVariable Long id, @RequestBody SetRolesReq req) {
        return admin.setRoles(id, req.roles());
    }

    // ----- tokens -----

    @GetMapping("/users/{id}/tokens")
    public List<TokenDto> listTokens(@PathVariable Long id) {
        return admin.listTokens(id);
    }

    @PostMapping("/users/{id}/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenSecretResp mintToken(@PathVariable Long id, @RequestBody CreateTokenReq req) {
        return new TokenSecretResp(admin.mintToken(id, req.label()));
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteToken(@PathVariable Long id) {
        admin.deleteToken(id);
    }

    // ----- roles & rules -----

    @GetMapping("/roles")
    public List<RoleDto> listRoles() {
        return admin.listRoles();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleDto createRole(@RequestBody CreateRoleReq req) {
        return admin.createRole(req.name());
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable Long id) {
        admin.deleteRole(id);
    }

    @PostMapping("/roles/{id}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.RuleDto addRule(@PathVariable Long id, @RequestBody CreateRuleReq req) {
        return admin.addRule(id, req.pathGlob(), req.canRead(), req.canWrite());
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable Long id) {
        admin.deleteRule(id);
    }

    // ----- repos & audit -----

    @GetMapping("/repos")
    public List<String> listRepos() {
        return repositories.listRepoNames();
    }

    @GetMapping("/audit")
    public List<AuditDto> audit() {
        return audit.recent().stream()
                .map(e -> new AuditDto(e.getTs(), e.getUsername(), e.getRepo(),
                        e.getAction(), e.getRef(), e.getDetail()))
                .toList();
    }
}
