package dev.gitgate.git;

/**
 * Signals that a pushed change cannot be applied to canonical history — e.g. a write
 * to a path the user lacks permission for, or a stale base that needs a pull first.
 * The message is surfaced to the git client as the push-rejection reason.
 */
public class ReconcileException extends RuntimeException {
    public ReconcileException(String message) {
        super(message);
    }
}
