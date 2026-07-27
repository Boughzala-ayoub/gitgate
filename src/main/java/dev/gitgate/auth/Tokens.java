package dev.gitgate.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Token hashing + generation. Tokens are high-entropy, so a plain SHA-256 is sufficient. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available
        }
    }

    /** Generates a new random token secret to hand to a user (shown once). */
    public static String generate() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
