package dev.gitgate.acl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The path-matching rules every access decision ultimately rests on. */
class GlobMatcherTest {

    @Test
    void plainNameGrantsItsSubtreeButNotSiblingPrefixes() {
        GlobMatcher matcher = GlobMatcher.of(List.of("backend"));

        assertTrue(matcher.isReadable("backend"));
        assertTrue(matcher.isReadable("backend/db.js"));
        assertTrue(matcher.isReadable("backend/deep/nested/file.txt"));
        assertFalse(matcher.isReadable("backend-secrets/db.js"));
        assertFalse(matcher.isReadable("frontend/app.js"));
    }

    @Test
    void singleStarDoesNotCrossDirectories() {
        GlobMatcher matcher = GlobMatcher.of(List.of("*.md"));

        assertTrue(matcher.isReadable("README.md"));
        assertFalse(matcher.isReadable("docs/guide.md"));
        assertFalse(matcher.isReadable("README.txt"));
    }

    @Test
    void doubleStarCrossesDirectories() {
        assertTrue(GlobMatcher.of(List.of("**")).isReadable("a/b/c.txt"));
        assertTrue(GlobMatcher.of(List.of("docs/**")).isReadable("docs/a/b.txt"));
        assertFalse(GlobMatcher.of(List.of("docs/**")).isReadable("src/a.txt"));
    }

    @Test
    void questionMarkMatchesExactlyOneNonSlashCharacter() {
        GlobMatcher matcher = GlobMatcher.of(List.of("log?.txt"));

        assertTrue(matcher.isReadable("log1.txt"));
        assertFalse(matcher.isReadable("log12.txt"));
        assertFalse(matcher.isReadable("log/.txt"));
    }

    @Test
    void regexMetacharactersInGlobsAreLiteral() {
        GlobMatcher matcher = GlobMatcher.of(List.of("a+b(c).txt"));

        assertTrue(matcher.isReadable("a+b(c).txt"));
        assertFalse(matcher.isReadable("aab(c)Xtxt"));
    }

    @Test
    void anExclusionCarvesAPathOutOfAGrant() {
        // "the whole backend folder, except the secrets file"
        GlobMatcher matcher = GlobMatcher.of(List.of("backend"), List.of("backend/secrets.env"));

        assertTrue(matcher.isReadable("backend/db.js"));
        assertTrue(matcher.isReadable("backend/deep/thing.js"));
        assertFalse(matcher.isReadable("backend/secrets.env"));
        assertTrue(matcher.isExcluded("backend/secrets.env"));
    }

    @Test
    void exclusionsBeatEvenTheBroadestGrant() {
        // "public, except anything under private/ and any .env file anywhere"
        GlobMatcher matcher = GlobMatcher.of(List.of("**"), List.of("private/**", "**/*.env", "*.env"));

        assertTrue(matcher.isReadable("README.md"));
        assertTrue(matcher.isReadable("src/app.js"));
        assertFalse(matcher.isReadable("private/keys.txt"));
        assertFalse(matcher.isReadable("config/prod.env"));
        assertFalse(matcher.isReadable(".env"));
    }

    @Test
    void anExclusionCannotBeDefeatedByAMoreSpecificGrant() {
        // Order and specificity must not matter: deny always wins, so an operator can't be
        // tricked into re-granting a path they deliberately carved out.
        GlobMatcher matcher = GlobMatcher.of(List.of("**", "secret.txt", "vault"), List.of("secret.txt", "vault"));

        assertFalse(matcher.isReadable("secret.txt"));
        assertFalse(matcher.isReadable("vault/key.pem"), "excluding a folder excludes its subtree");
        assertTrue(matcher.isReadable("other.txt"));
    }

    @Test
    void noGlobsMeansDenyEverything() {
        GlobMatcher matcher = GlobMatcher.of(List.of());

        assertFalse(matcher.isReadable("README.md"));
        assertFalse(matcher.isReadable(""));
    }
}
