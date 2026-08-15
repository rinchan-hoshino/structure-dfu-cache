package dev.rinchan.structuredfucache.cache;

import java.time.Duration;

public record CachePolicy(Duration timeout) {
    private static final int MINIMUM_SECONDS = 60;
    private static final int MAXIMUM_SECONDS = 1800;

    public CachePolicy {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be null or negative");
        }
    }

    public static CachePolicy fromSeconds(int seconds) {
        if (seconds == 0) {
            return new CachePolicy(Duration.ZERO);
        }
        if (seconds < MINIMUM_SECONDS || seconds > MAXIMUM_SECONDS) {
            throw new IllegalArgumentException("cold build timeout must be 0 or between 60 and 1800 seconds");
        }
        return new CachePolicy(Duration.ofSeconds(seconds));
    }

    public boolean hasTimeout() {
        return !timeout.isZero();
    }
}
