package io.github.hectorvent.floci.services.codebuild;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CodeBuildFleetCapacityTest {

    private static final String FLEET = "arn:aws:codebuild:us-east-1:000000000000:fleet/f:1";

    private final CodeBuildFleetCapacity capacity = new CodeBuildFleetCapacity();

    @Test
    void aFullFleetQueuesTheNextBuildUntilASlotFrees() throws Exception {
        assertEquals(CodeBuildFleetCapacity.Slot.ACQUIRED,
                capacity.acquire(FLEET, () -> 1, false, () -> false, 1000));

        CompletableFuture<CodeBuildFleetCapacity.Slot> queued = CompletableFuture.supplyAsync(() -> {
            try {
                return capacity.acquire(FLEET, () -> 1, false, () -> false, 10_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(300);
        assertFalse(queued.isDone());

        capacity.release(FLEET);
        assertEquals(CodeBuildFleetCapacity.Slot.ACQUIRED, queued.get(5, TimeUnit.SECONDS));
        assertEquals(1, capacity.running(FLEET));
    }

    @Test
    void overflowRunsOnDemandWhenTheFleetSaysSo() throws Exception {
        capacity.acquire(FLEET, () -> 1, true, () -> false, 1000);

        assertEquals(CodeBuildFleetCapacity.Slot.ON_DEMAND,
                capacity.acquire(FLEET, () -> 1, true, () -> false, 1000));
        assertEquals(1, capacity.running(FLEET));
    }

    @Test
    void aQueuedBuildEndsWhenStoppedOrTimedOut() throws Exception {
        capacity.acquire(FLEET, () -> 1, false, () -> false, 1000);
        AtomicBoolean stopped = new AtomicBoolean(true);

        assertEquals(CodeBuildFleetCapacity.Slot.STOPPED, capacity.acquire(FLEET, () -> 1, false, stopped::get, 5000));
        assertEquals(CodeBuildFleetCapacity.Slot.TIMED_OUT, capacity.acquire(FLEET, () -> 1, false, () -> false, 100));
    }

    @Test
    void raisingTheCapacityLetsAQueuedBuildIn() throws Exception {
        capacity.acquire(FLEET, () -> 1, false, () -> false, 1000);

        assertEquals(CodeBuildFleetCapacity.Slot.ACQUIRED, capacity.acquire(FLEET, () -> 2, false, () -> false, 1000));
    }
}
