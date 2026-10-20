package io.github.hectorvent.floci.services.auditmanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A control set inside a framework, holding the ids of the controls it groups. */
@RegisterForReflection
public class FrameworkControlSet {

    private String id;
    private String name;
    private List<String> controlIds = new ArrayList<>();

    public FrameworkControlSet() {}

    public FrameworkControlSet(String id, String name, List<String> controlIds) {
        this.id = id;
        this.name = name;
        this.controlIds = controlIds;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getControlIds() { return controlIds; }
    public void setControlIds(List<String> controlIds) { this.controlIds = controlIds; }
}
