package dev.rinchan.structuredfucache.cache;

public final class CacheBuildException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CacheBuildException(String message) {
        super(message);
    }

    public CacheBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
