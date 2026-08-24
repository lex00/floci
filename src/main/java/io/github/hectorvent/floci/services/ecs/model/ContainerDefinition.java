package io.github.hectorvent.floci.services.ecs.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class ContainerDefinition {

    private String name;
    private String image;
    private Integer cpu;
    private Integer memory;
    private Integer memoryReservation;
    private boolean essential = true;
    private List<PortMapping> portMappings;
    private List<KeyValuePair> environment;
    private List<Secret> secrets;
    private List<String> command;
    private List<String> entryPoint;
    private List<MountPoint> mountPoints;
    private LogConfiguration logConfiguration;

    // The exact JSON object this container definition was registered with. RegisterTaskDefinition
    // is a store-and-echo API: everything a caller sends comes back unchanged from the following
    // Describe/Register response, whether or not floci has a typed field for it (dependsOn,
    // linuxParameters, restartPolicy, versionConsistency, startTimeout/stopTimeout, interactive,
    // pseudoTerminal, privileged, readonlyRootFilesystem, user, firelensConfiguration,
    // volumesFrom, and the rest of hashicorp/aws's ContainerDefinition schema - reproduced
    // directly against AWS's own botocore ECS model, which has no "input only" fields here).
    // Rendering merges this raw copy first and lets the handful of fields floci actually
    // computes or defaults (name, image, essential, portMappings, ...) win over it, so a field
    // this class never learns to parse still round-trips instead of silently vanishing.
    private JsonNode raw;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public Integer getCpu() { return cpu; }
    public void setCpu(Integer cpu) { this.cpu = cpu; }

    public Integer getMemory() { return memory; }
    public void setMemory(Integer memory) { this.memory = memory; }

    public Integer getMemoryReservation() { return memoryReservation; }
    public void setMemoryReservation(Integer memoryReservation) { this.memoryReservation = memoryReservation; }

    public boolean isEssential() { return essential; }
    public void setEssential(boolean essential) { this.essential = essential; }

    public List<PortMapping> getPortMappings() { return portMappings; }
    public void setPortMappings(List<PortMapping> portMappings) { this.portMappings = portMappings; }

    public List<KeyValuePair> getEnvironment() { return environment; }
    public void setEnvironment(List<KeyValuePair> environment) { this.environment = environment; }

    public List<Secret> getSecrets() { return secrets; }
    public void setSecrets(List<Secret> secrets) { this.secrets = secrets; }

    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }

    public List<String> getEntryPoint() { return entryPoint; }
    public void setEntryPoint(List<String> entryPoint) { this.entryPoint = entryPoint; }

    public List<MountPoint> getMountPoints() { return mountPoints; }
    public void setMountPoints(List<MountPoint> mountPoints) { this.mountPoints = mountPoints; }

    public LogConfiguration getLogConfiguration() { return logConfiguration; }
    public void setLogConfiguration(LogConfiguration logConfiguration) { this.logConfiguration = logConfiguration; }

    public JsonNode getRaw() { return raw; }
    public void setRaw(JsonNode raw) { this.raw = raw; }
}
