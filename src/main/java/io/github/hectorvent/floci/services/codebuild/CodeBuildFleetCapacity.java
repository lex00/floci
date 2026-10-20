package io.github.hectorvent.floci.services.codebuild;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The build slots of each compute fleet. A fleet runs as many builds at once as its base capacity;
 * a build that finds the fleet full waits in QUEUED for a slot, or runs on demand when the fleet's
 * overflow behavior is {@code ON_DEMAND}.
 */
@ApplicationScoped
public class CodeBuildFleetCapacity {

    private static final long POLL_MILLIS = 200;

    private final Map<String, Integer> running = new HashMap<>();

    /** The outcome of waiting for a slot. */
    public enum Slot { ACQUIRED, ON_DEMAND, STOPPED, TIMED_OUT }

    /**
     * Takes a slot of the fleet, waiting while it is full.
     *
     * @param capacity the fleet's current base capacity, read on every check so an update applies
     * @param onDemand whether a full fleet sends the build to on-demand capacity instead of queueing it
     * @param stopped whether the build was stopped while it waited
     * @param timeoutMillis how long the build may wait in the queue
     */
    public Slot acquire(String fleetArn, IntSupplier capacity, boolean onDemand, BooleanSupplier stopped,
                        long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (running) {
            while (true) {
                int inUse = running.getOrDefault(fleetArn, 0);
                if (inUse < capacity.getAsInt()) {
                    running.put(fleetArn, inUse + 1);
                    return Slot.ACQUIRED;
                }
                if (onDemand) {
                    return Slot.ON_DEMAND;
                }
                if (stopped.getAsBoolean()) {
                    return Slot.STOPPED;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return Slot.TIMED_OUT;
                }
                running.wait(Math.min(remaining, POLL_MILLIS));
            }
        }
    }

    public void release(String fleetArn) {
        synchronized (running) {
            running.computeIfPresent(fleetArn, (arn, inUse) -> inUse > 1 ? inUse - 1 : null);
            running.notifyAll();
        }
    }

    /** Builds running on the fleet now. */
    public int running(String fleetArn) {
        synchronized (running) {
            return running.getOrDefault(fleetArn, 0);
        }
    }
}
