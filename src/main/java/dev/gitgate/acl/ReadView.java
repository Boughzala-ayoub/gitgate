package dev.gitgate.acl;

import java.util.List;

/**
 * A user's resolved access to a repository.
 *
 * @param globs        the union of read-globs from all the user's roles (sorted, de-duped)
 * @param viewId       a stable id derived from {@code globs}; users with identical read
 *                     permissions share a view (and its materialized cache)
 * @param matcher      compiled matcher for the read-globs
 * @param writeMatcher compiled matcher for the user's write-globs
 */
public record ReadView(List<String> globs, String viewId, GlobMatcher matcher, GlobMatcher writeMatcher) {

    /** True if this view grants no read paths at all (user sees an empty repo). */
    public boolean isEmpty() {
        return globs.isEmpty();
    }
}
