package dev.gitgate.api;

import dev.gitgate.api.Dtos.MeDto;
import dev.gitgate.api.Dtos.SignupReq;
import dev.gitgate.auth.Tokens;
import dev.gitgate.store.AppUser;
import dev.gitgate.store.AppUserRepository;
import dev.gitgate.store.Token;
import dev.gitgate.store.TokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Public self-service sign-up. A user picks a password, which is stored as a hashed token
 * ("password" label) — consistent with the rest of GitGate's token auth. New accounts are
 * ordinary users (never super-admin).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository users;
    private final TokenRepository tokens;

    public AuthController(AppUserRepository users, TokenRepository tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public MeDto signup(@RequestBody SignupReq req) {
        String username = req.username();
        if (username == null || !username.matches("[a-zA-Z0-9._-]{3,30}")) {
            throw new IllegalArgumentException("username must be 3-30 chars: letters, digits, . _ -");
        }
        if (req.password() == null || req.password().length() < 4) {
            throw new IllegalArgumentException("password must be at least 4 characters");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("username already taken");
        }
        AppUser user = users.save(new AppUser(username));
        tokens.save(new Token(user, Tokens.sha256Hex(req.password()), "password", Instant.now()));
        return new MeDto(username, false);
    }
}
