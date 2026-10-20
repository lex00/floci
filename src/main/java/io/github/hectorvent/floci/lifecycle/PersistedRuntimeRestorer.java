package io.github.hectorvent.floci.lifecycle;

/**
 * A service that brings its persisted resources' runtime back at startup, after storage loads.
 * {@link EmulatorLifecycle} calls every implementation once the built-in restores have run.
 */
public interface PersistedRuntimeRestorer {

    void restorePersistedRuntime();
}
