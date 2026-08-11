package com.concentus.model;

/**
 * An organization-level prompt variable: {@code {{NAME}}} anywhere in a flow's prompts becomes
 * {@code value} when a run starts. A flow can override any of these — or add its own — in its
 * settings; the flow's map wins on collision, and is saved with the flow, which is what makes a
 * flow remember the values it runs with.
 */
public record Variable(String id, String name, String value, String description) {

    public Variable withId(String newId) {
        return new Variable(newId, name, value, description);
    }
}
