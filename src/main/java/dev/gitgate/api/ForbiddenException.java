package dev.gitgate.api;

/** Thrown when an authenticated user lacks permission for an action (mapped to HTTP 403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
