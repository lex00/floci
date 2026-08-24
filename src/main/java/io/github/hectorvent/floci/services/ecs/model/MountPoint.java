package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A container mount point in an ECS container definition:
 * {@code {"sourceVolume": ..., "containerPath": ..., "readOnly": ...}}.
 * {@code sourceVolume} references a task-level {@link Volume} by name; the volume's
 * host source path is bind-mounted into the container at {@code containerPath}.
 *
 * {@code readOnly} is nullable, not a primitive {@code boolean}: real AWS documents its
 * default as false but leaves the key entirely absent from RegisterTaskDefinition/
 * DescribeTaskDefinition responses when the caller never set it - confirmed live against
 * real AWS (register-task-definition with no readOnly on a mount point; the response's
 * mountPoints entry carries no readOnly key at all, not readOnly=false). A primitive here
 * would force every mount point to echo readOnly=false on write, which terraform-provider-
 * aws's container_definitions equivalence check does not expect and reads as a genuine
 * diff, forcing needless task-definition replacement (lex00/floci#131).
 */
@RegisterForReflection
public record MountPoint(String sourceVolume, String containerPath, Boolean readOnly) {
}
