package io.github.hectorvent.floci.services.route53;

/**
 * A health check's status as its probe results move it. The check starts healthy and changes
 * status only after {@code FailureThreshold} consecutive results that disagree with it, as AWS's
 * checkers do.
 */
final class HealthCheckProbeState {

    private boolean healthy = true;
    private int consecutiveSuccesses;
    private int consecutiveFailures;

    synchronized void record(boolean success, int failureThreshold) {
        int threshold = Math.max(1, failureThreshold);
        if (success) {
            consecutiveSuccesses++;
            consecutiveFailures = 0;
            if (!healthy && consecutiveSuccesses >= threshold) {
                healthy = true;
            }
        } else {
            consecutiveFailures++;
            consecutiveSuccesses = 0;
            if (healthy && consecutiveFailures >= threshold) {
                healthy = false;
            }
        }
    }

    synchronized boolean healthy() {
        return healthy;
    }

    /**
     * The status Route 53 acts on. A disabled check counts as healthy, and {@code Inverted} then
     * flips whichever status applies, so inverting a disabled check makes it unhealthy.
     */
    static boolean reported(boolean healthy, boolean inverted, boolean disabled) {
        return (disabled || healthy) != inverted;
    }
}
