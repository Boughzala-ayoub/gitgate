package dev.gitgate.acl;

import java.util.List;

/**
 * A user's resolved access to a repository.
 *
 * @param globs        the union of read-globs from all the user's roles (sorted, de-duped)
 * @param exclusions   paths carved back out of {@code globs} (sorted, de-duped)
 * @param viewId       a stable id derived from {@code globs} <em>and</em> {@code exclusions};
 *                     users with identical read permissions share a view (and its materialized
 *                     cache), so both halves must feed the id or one user's exclusions would
 *                     leak into another's view
 * @param matcher      compiled matcher for the read-globs, exclusions applied
 * @param writeMatcher compiled matcher for the user's write-globs, exclusions applied
 */
public record ReadView(List<String> globs, List<String> exclusions, String viewId,
                       GlobMatcher matcher, GlobMatcher writeMatcher) {

    /** True if this view grants no read paths at all (user sees an empty repo). */
    public boolean isEmpty() {
        return globs.isEmpty();
    }
}
