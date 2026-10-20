package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteLagTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void deletedValueStaysVisibleUntilTheWindowCloses() {
        DeleteLag<String> lag = new DeleteLag<>(Duration.ofSeconds(30), clock);
        lag.deleted("acct/a", "A");

        assertEquals(Optional.of("A"), lag.get("acct/a"));
        assertEquals(List.of("A"), lag.values(k -> k.startsWith("acct/")));

        clock.advance(Duration.ofSeconds(30));

        assertTrue(lag.get("acct/a").isEmpty());
        assertTrue(lag.values(k -> true).isEmpty());
    }

    @Test
    void zeroWindowRecordsNothing() {
        DeleteLag<String> lag = new DeleteLag<>(Duration.ZERO, clock);
        lag.deleted("acct/a", "A");

        assertTrue(lag.get("acct/a").isEmpty());
    }

    @Test
    void forgetDropsTheValueAtOnce() {
        DeleteLag<String> lag = new DeleteLag<>(Duration.ofSeconds(30), clock);
        lag.deleted("acct/a", "A");
        lag.forget("acct/a");

        assertTrue(lag.get("acct/a").isEmpty());
    }
}
