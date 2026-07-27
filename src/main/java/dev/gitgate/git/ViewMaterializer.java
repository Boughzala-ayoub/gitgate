package dev.gitgate.git;

import dev.gitgate.acl.GlobMatcher;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a canonical repository's history into a per-user <em>filtered view</em>.
 *
 * <p>For each branch, every commit is rewritten so its tree contains only the paths
 * the view may read; parents are rewritten to their view equivalents; author,
 * committer, message, and timestamps are preserved. The rewrite is a pure function
 * of (canonical commit, view), so the same view always yields the same object ids —
 * which is what lets {@code git fetch} work incrementally.
 *
 * <p>Readable blobs are <strong>not</strong> copied: their ids are unchanged, and the
 * view repository resolves them through an {@code objects/info/alternates} link back
 * to the canonical store. Only trees and commits are newly written.
 *
 * <p>Phase 1 re-materializes on each access. Because the rewrite is deterministic and
 * memoized within a pass, this is correct and idempotent; Postgres-backed cross-request
 * caching is a later optimization.
 */
@Component
public class ViewMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ViewMaterializer.class);

    /**
     * Brings {@code view}'s branches up to date as the filtered projection of
     * {@code canonical} under {@code matcher}.
     *
     * @return the memoized canonical-commit &rarr; view-commit map built during this pass.
     *         The push reconciler inverts it to map a pushed view-commit's parent back to
     *         canonical history.
     */
    public Map<ObjectId, ObjectId> materialize(Repository canonical, Repository view, GlobMatcher matcher)
            throws IOException {
        List<Ref> heads = canonical.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        Map<ObjectId, ObjectId> memo = new HashMap<>();

        try (RevWalk walk = new RevWalk(canonical);
             ObjectInserter inserter = view.newObjectInserter()) {

            for (Ref head : heads) {
                ObjectId canonicalTip = head.getObjectId();
                if (canonicalTip == null) {
                    continue;
                }
                ObjectId viewTip = materializeCommit(canonicalTip, canonical, walk, inserter, matcher, memo);
                setRef(view, head.getName(), viewTip);
            }
            inserter.flush();
        }

        copyHead(canonical, view);
        log.debug("Materialized {} branch(es) into view {}", heads.size(), view.getDirectory());
        return memo;
    }

    /**
     * Returns the view-commit id for a canonical commit, rewriting (and memoizing) as needed.
     *
     * <p>Walks the ancestry with an explicit stack rather than recursion: history is as deep as
     * the repo has commits, and recursing per-commit overflows the JVM stack on real repositories.
     */
    private ObjectId materializeCommit(ObjectId root, Repository canonical, RevWalk walk,
                                       ObjectInserter inserter, GlobMatcher matcher,
                                       Map<ObjectId, ObjectId> memo) throws IOException {
        Deque<ObjectId> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            ObjectId canonicalId = stack.peek();
            if (memo.containsKey(canonicalId)) {
                stack.pop();
                continue;
            }

            RevCommit commit = walk.parseCommit(canonicalId);

            // Parents must be rewritten first — push any that aren't done and revisit this commit.
            boolean awaitingParents = false;
            for (RevCommit parent : commit.getParents()) {
                if (!memo.containsKey(parent.getId())) {
                    stack.push(parent.getId());
                    awaitingParents = true;
                }
            }
            if (awaitingParents) {
                continue;
            }

            ObjectId filteredTree = filterTree(commit.getTree(), "", canonical, inserter, matcher);
            if (filteredTree == null) {
                filteredTree = inserter.insert(new TreeFormatter()); // empty tree
            }

            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(filteredTree);
            builder.setAuthor(commit.getAuthorIdent());
            builder.setCommitter(commit.getCommitterIdent());
            builder.setMessage(commit.getFullMessage());
            builder.setEncoding(commit.getEncoding());

            ObjectId[] parents = new ObjectId[commit.getParentCount()];
            for (int i = 0; i < parents.length; i++) {
                parents[i] = memo.get(commit.getParent(i).getId());
            }
            builder.setParentIds(parents);

            memo.put(canonicalId, inserter.insert(builder));
            stack.pop();
        }

        return memo.get(root);
    }

    /**
     * Rewrites a tree to contain only readable entries.
     *
     * @param prefix the path of this tree within the repo ("" at the root)
     * @return the rewritten tree's id, or {@code null} if it has no readable entries
     *         (so the caller omits this subtree entirely)
     */
    private ObjectId filterTree(ObjectId treeId, String prefix, Repository canonical,
                                ObjectInserter inserter, GlobMatcher matcher) throws IOException {
        TreeFormatter formatter = new TreeFormatter();
        boolean anyKept = false;

        try (TreeWalk walk = new TreeWalk(canonical)) {
            walk.addTree(treeId);
            walk.setRecursive(false);
            while (walk.next()) {
                String name = walk.getNameString();
                FileMode mode = walk.getFileMode(0);
                String path = prefix.isEmpty() ? name : prefix + "/" + name;

                if (FileMode.TREE.equals(mode)) {
                    ObjectId sub = filterTree(walk.getObjectId(0), path, canonical, inserter, matcher);
                    if (sub != null) {
                        formatter.append(name, FileMode.TREE, sub);
                        anyKept = true;
                    }
                } else if (matcher.isReadable(path)) {
                    // Readable blob / symlink / gitlink: keep id and mode unchanged.
                    formatter.append(name, mode, walk.getObjectId(0));
                    anyKept = true;
                }
            }
        }

        return anyKept ? inserter.insert(formatter) : null;
    }

    private void setRef(Repository view, String name, ObjectId target) throws IOException {
        RefUpdate update = view.updateRef(name);
        update.setNewObjectId(target);
        update.setForceUpdate(true);
        update.disableRefLog();
        update.update();
    }

    /** Points the view's HEAD at the same branch the canonical repo's HEAD tracks. */
    private void copyHead(Repository canonical, Repository view) throws IOException {
        String branch = canonical.getFullBranch(); // e.g. refs/heads/main
        if (branch != null && branch.startsWith(Constants.R_HEADS) && view.exactRef(branch) != null) {
            RefUpdate head = view.updateRef(Constants.HEAD);
            head.disableRefLog();
            head.link(branch);
        }
    }
}
