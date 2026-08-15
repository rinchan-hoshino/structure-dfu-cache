package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CachePolicyTest {
    @Test
    void defaultsToFiveMinuteFailClosedBudget() {
        CachePolicy policy = CachePolicy.fromSeconds(300);

        assertTrue(policy.hasTimeout());
        assertEquals(Duration.ofMinutes(5), policy.timeout());
    }

    @Test
    void zeroExplicitlyAllowsUnlimitedColdBuild() {
        CachePolicy policy = CachePolicy.fromSeconds(0);

        assertFalse(policy.hasTimeout());
    }

    @Test
    void boundedValuesMustStayBetweenOneAndThirtyMinutes() {
        assertEquals(Duration.ofSeconds(60), CachePolicy.fromSeconds(60).timeout());
        assertEquals(Duration.ofSeconds(1800), CachePolicy.fromSeconds(1800).timeout());
        assertThrows(IllegalArgumentException.class, () -> CachePolicy.fromSeconds(59));
        assertThrows(IllegalArgumentException.class, () -> CachePolicy.fromSeconds(1801));
        assertThrows(IllegalArgumentException.class, () -> CachePolicy.fromSeconds(-1));
    }
}
