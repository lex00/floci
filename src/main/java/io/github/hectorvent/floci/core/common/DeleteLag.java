package io.github.hectorvent.floci.core.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Deleted resources that a service's reads keep returning for a configured window, as AWS's
 * eventually consistent deletes do. A zero window records nothing, so a delete is immediate.
 *
 * <p>Only read paths consult it. Writes see the resource as gone from the moment it is deleted.
 */
public final class DeleteLag<V> {

    private record Pending<V>(V value, Instant visibleUntil) {}

    private final ConcurrentHashMap<String, Pending<V>> pending = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile Duration window;

    public DeleteLag(Duration window, Clock clock) {
        this.window = window.isNegative() ? Duration.ZERO : window;
        this.clock = clock;
    }

    public static <V> DeleteLag<V> ofSeconds(int seconds) {
        return new DeleteLag<>(Duration.ofSeconds(Math.max(0, seconds)), Clock.systemUTC());
    }

    public void setWindow(Duration window) {
        this.window = window.isNegative() ? Duration.ZERO : window;
    }

    /** Records a resource that was just deleted, when the window is open. */
    public void deleted(String key, V value) {
        Duration current = window;
        if (current.isPositive()) {
            pending.put(key, new Pending<>(value, clock.instant().plus(current)));
        }
    }

    /** Drops a key at once, for a resource created again under the same identity. */
    public void forget(String key) {
        pending.remove(key);
    }

    /** The resource a recent delete still shows to reads under this key. */
    public Optional<V> get(String key) {
        Pending<V> entry = pending.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.visibleUntil())) {
            pending.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** Every resource still visible whose key passes the filter. */
    public List<V> values(Predicate<String> keyFilter) {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<V> visible = new ArrayList<>();
        for (Map.Entry<String, Pending<V>> entry : pending.entrySet()) {
            if (keyFilter.test(entry.getKey())) {
                get(entry.getKey()).ifPresent(visible::add);
            }
        }
        return visible;
    }

    public void clear() {
        pending.clear();
    }
}
