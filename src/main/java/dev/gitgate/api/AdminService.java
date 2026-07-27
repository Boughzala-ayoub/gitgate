package dev.gitgate.api;

import dev.gitgate.api.Dtos.RoleDto;
import dev.gitgate.api.Dtos.RuleDto;
import dev.gitgate.api.Dtos.TokenDto;
import dev.gitgate.api.Dtos.UserDto;
import dev.gitgate.auth.Tokens;
import dev.gitgate.store.AclRule;
import dev.gitgate.store.AclRuleRepository;
import dev.gitgate.store.AppUser;
import dev.gitgate.store.AppUserRepository;
import dev.gitgate.store.Role;
import dev.gitgate.store.RoleRepository;
import dev.gitgate.store.Token;
import dev.gitgate.store.TokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/** All admin mutations against the user/role/rule/token store. */
@Service
public class AdminService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final AclRuleRepository rules;
    private final TokenRepository tokens;

    public AdminService(AppUserRepository users, RoleRepository roles,
                        AclRuleRepository rules, TokenRepository tokens) {
        this.users = users;
        this.roles = roles;
        this.rules = rules;
        this.tokens = tokens;
    }

    // ----- users -----

    @Transactional(readOnly = true)
    public List<UserDto> listUsers() {
        return users.findAll().stream().map(this::toUserDto).toList();
    }

    @Transactional
    public UserDto createUser(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("user already exists: " + username);
        }
        return toUserDto(users.save(new AppUser(username)));
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser user = users.findById(id).orElseThrow(() -> notFound("user", id));
        tokens.findByUser(user).forEach(tokens::delete);
        users.delete(user);
    }

    @Transactional
    public UserDto setRoles(Long id, List<String> roleNames) {
        AppUser user = users.findById(id).orElseThrow(() -> notFound("user", id));
        Set<Role> assigned = roleNames.stream()
                .map(n -> roles.findByName(n).orElseThrow(() -> new IllegalArgumentException("no such role: " + n)))
                .collect(Collectors.toSet());
        user.setRoles(assigned);
        return toUserDto(users.save(user));
    }

    // ----- tokens -----

    @Transactional
    public String mintToken(Long userId, String label) {
        AppUser user = users.findById(userId).orElseThrow(() -> notFound("user", userId));
        String secret = Tokens.generate();
        tokens.save(new Token(user, Tokens.sha256Hex(secret), label, Instant.now()));
        return secret;
    }

    @Transactional(readOnly = true)
    public List<TokenDto> listTokens(Long userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> notFound("user", userId));
        return tokens.findByUser(user).stream()
                .map(t -> new TokenDto(t.getId(), t.getLabel(), t.getCreatedAt(), t.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public void deleteToken(Long id) {
        tokens.deleteById(id);
    }

    // ----- roles & rules -----

    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {
        return roles.findAll().stream()
                .map(r -> new RoleDto(r.getId(), r.getName(),
                        rules.findByRole(r).stream().map(this::toRuleDto).toList()))
                .toList();
    }

    @Transactional
    public RoleDto createRole(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("role name is required");
        }
        if (roles.findByName(name).isPresent()) {
            throw new IllegalArgumentException("role already exists: " + name);
        }
        Role role = roles.save(new Role(name));
        return new RoleDto(role.getId(), role.getName(), List.of());
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = roles.findById(id).orElseThrow(() -> notFound("role", id));
        rules.findByRole(role).forEach(rules::delete);
        // Unlink the role from any users that hold it.
        users.findAll().forEach(u -> {
            if (u.getRoles().removeIf(r -> r.getId().equals(id))) {
                users.save(u);
            }
        });
        roles.delete(role);
    }

    @Transactional
    public RuleDto addRule(Long roleId, String pathGlob, boolean canRead, boolean canWrite) {
        Role role = roles.findById(roleId).orElseThrow(() -> notFound("role", roleId));
        if (pathGlob == null || pathGlob.isBlank()) {
            throw new IllegalArgumentException("pathGlob is required");
        }
        return toRuleDto(rules.save(new AclRule(role, pathGlob, canRead, canWrite)));
    }

    @Transactional
    public void deleteRule(Long id) {
        rules.deleteById(id);
    }

    // ----- mapping helpers -----

    private UserDto toUserDto(AppUser u) {
        List<String> roleNames = u.getRoles().stream().map(Role::getName).sorted().toList();
        return new UserDto(u.getId(), u.getUsername(), roleNames, tokens.findByUser(u).size());
    }

    private RuleDto toRuleDto(AclRule a) {
        return new RuleDto(a.getId(), a.getPathGlob(), a.isCanRead(), a.isCanWrite());
    }

    private NoSuchElementException notFound(String what, Long id) {
        return new NoSuchElementException(what + " not found: " + id);
    }
}
