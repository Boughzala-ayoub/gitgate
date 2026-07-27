package dev.gitgate.auth;

import dev.gitgate.store.AppUser;
import dev.gitgate.store.AppUserRepository;
import dev.gitgate.store.Token;
import dev.gitgate.store.TokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Authenticates a (username, token) pair against the stored token hashes. */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final TokenRepository tokens;

    public AuthService(AppUserRepository users, TokenRepository tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** @return the username if the secret matches one of the user's tokens, else empty. */
    @Transactional
    public Optional<String> authenticate(String username, String secret) {
        AppUser user = users.findByUsername(username).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        String hash = Tokens.sha256Hex(secret);
        for (Token token : tokens.findByUser(user)) {
            if (token.getTokenHash().equals(hash)) {
                token.setLastUsedAt(Instant.now());
                return Optional.of(username);
            }
        }
        return Optional.empty();
    }
}
