package dev.gitgate.acl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches forward-slash repository paths against a set of gitignore-flavored globs.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>{@code **} — any number of characters, including {@code /}</li>
 *   <li>{@code *}  — any run of non-{@code /} characters</li>
 *   <li>{@code ?}  — a single non-{@code /} character</li>
 * </ul>
 * As a convenience, a glob with no wildcard also grants its entire subtree:
 * {@code "backend"} matches {@code "backend"} and any {@code "backend/..."}.
 *
 * <p>A path is readable if it matches an <em>allow</em> glob and no <em>exclusion</em> glob
 * (default deny; an exclusion always wins). Exclusions are what make "this whole folder except
 * that one file" expressible — without them every allowed path would have to be enumerated.
 */
public final class GlobMatcher {

    private record Rule(String glob, Pattern pattern, boolean hasWildcard) {}

    private final List<Rule> allow;
    private final List<Rule> deny;

    private GlobMatcher(List<Rule> allow, List<Rule> deny) {
        this.allow = allow;
        this.deny = deny;
    }

    public static GlobMatcher of(Collection<String> globs) {
        return of(globs, List.of());
    }

    /**
     * @param globs      paths this view may see
     * @param exclusions paths carved back out of {@code globs}, whatever they match
     */
    public static GlobMatcher of(Collection<String> globs, Collection<String> exclusions) {
        return new GlobMatcher(compile(globs), compile(exclusions));
    }

    private static List<Rule> compile(Collection<String> globs) {
        List<Rule> rules = new ArrayList<>();
        for (String glob : globs) {
            boolean wild = glob.indexOf('*') >= 0 || glob.indexOf('?') >= 0;
            rules.add(new Rule(glob, Pattern.compile(toRegex(glob)), wild));
        }
        return rules;
    }

    /** @return true if {@code path} (a forward-slash repo path) is granted and not excluded. */
    public boolean isReadable(String path) {
        return matches(allow, path) && !matches(deny, path);
    }

    /** True if {@code path} is carved out by an exclusion — used to explain access, not to gate it. */
    public boolean isExcluded(String path) {
        return matches(deny, path);
    }

    private static boolean matches(List<Rule> rules, String path) {
        for (Rule rule : rules) {
            if (rule.pattern.matcher(path).matches()) {
                return true;
            }
            if (!rule.hasWildcard
                    && (path.equals(rule.glob) || path.startsWith(rule.glob + "/"))) {
                return true; // plain name covers its whole subtree
            }
        }
        return false;
    }

    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.append('$').toString();
    }
}
