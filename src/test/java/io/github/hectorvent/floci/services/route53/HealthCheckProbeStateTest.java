package io.github.hectorvent.floci.services.route53;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCheckProbeStateTest {

    @Test
    void flipsOnlyAfterFailureThresholdConsecutiveDisagreements() {
        HealthCheckProbeState state = new HealthCheckProbeState();
        assertTrue(state.healthy());

        state.record(false, 3);
        state.record(false, 3);
        assertTrue(state.healthy());
        state.record(true, 3);
        state.record(false, 3);
        state.record(false, 3);
        assertTrue(state.healthy(), "a success in between restarts the count");
        state.record(false, 3);
        assertFalse(state.healthy());

        state.record(true, 3);
        state.record(true, 3);
        assertFalse(state.healthy());
        state.record(true, 3);
        assertTrue(state.healthy());
    }

    @Test
    void thresholdOfOneFollowsEveryResult() {
        HealthCheckProbeState state = new HealthCheckProbeState();
        state.record(false, 1);
        assertFalse(state.healthy());
        state.record(true, 1);
        assertTrue(state.healthy());
    }

    @Test
    void invertedFlipsAndDisabledCountsAsHealthyBeforeInversion() {
        assertTrue(HealthCheckProbeState.reported(true, false, false));
        assertFalse(HealthCheckProbeState.reported(false, false, false));
        assertFalse(HealthCheckProbeState.reported(true, true, false));
        assertTrue(HealthCheckProbeState.reported(false, true, false));
        assertTrue(HealthCheckProbeState.reported(false, false, true));
        assertFalse(HealthCheckProbeState.reported(false, true, true));
    }
}
