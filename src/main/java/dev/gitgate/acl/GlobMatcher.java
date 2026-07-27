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
 * <p>A path is readable if it matches any glob (default deny).
 */
public final class GlobMatcher {

    private record Rule(String glob, Pattern pattern, boolean hasWildcard) {}

    private final List<Rule> rules;

    private GlobMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    public static GlobMatcher of(Collection<String> globs) {
        List<Rule> rules = new ArrayList<>();
        for (String glob : globs) {
            boolean wild = glob.indexOf('*') >= 0 || glob.indexOf('?') >= 0;
            rules.add(new Rule(glob, Pattern.compile(toRegex(glob)), wild));
        }
        return new GlobMatcher(rules);
    }

    /** @return true if {@code path} (a forward-slash repo path) is granted by any glob. */
    public boolean isReadable(String path) {
        for (Rule rule : rules) {
            if (rule.pattern.matcher(path).matches()) {
                return true;
            }
            if (!rule.hasWildcard
                    && (path.equals(rule.glob) || path.startsWith(rule.glob + "/"))) {
                return true; // plain name grants its whole subtree
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
