package dev.gitgate.store;

/**
 * A collaborator's base role on a repo. When a membership has no explicit path scopes,
 * the role grants whole-repo access: READ → read-all, WRITE → read+write-all, ADMIN → full.
 * Path scopes, when present, narrow this to specific paths.
 */
public enum MembershipRole {
    READ,
    WRITE,
    ADMIN
}
