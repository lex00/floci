package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PortMapping(int containerPort, int hostPort, String protocol, String name) {

    public PortMapping(int containerPort, int hostPort, String protocol) {
        this(containerPort, hostPort, protocol, null);
    }

    public PortMapping(int containerPort) {
        this(containerPort, 0, "tcp", null);
    }
}
