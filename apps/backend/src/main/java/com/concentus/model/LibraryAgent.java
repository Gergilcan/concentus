package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A reusable agent definition from the agent library.
 *
 * <p>An agent block can hold a copy of one of these, or a <b>link</b> to it. A linked block keeps
 * only the id and the version it last took; at compile time the six definition fields — name,
 * model, effort, maxTokens, systemPrompt, description — are read from the library, so editing the
 * library agent reaches every flow that links it on its next run. Twenty flows with "the same
 * reviewer" are then one reviewer, not twenty copies that drift apart.
 *
 * @param version counts saves, from 1. Absent in a record written before versions existed, which
 *                reads as 1 — the canonical constructor normalises it, so nothing downstream has
 *                to remember that a zero means "the first version". The store assigns it; a
 *                version sent by a client is only honoured for an id the store has never seen,
 *                which is what restoring a backup needs.
 */
public record LibraryAgent(String id, String name, String model, String effort,
                           long maxTokens, String systemPrompt, String description, long version) {

    public LibraryAgent {
        if (version < 1) version = 1;
        // Rows from before the field existed have no description at all; a linked block reads
        // this straight into its routing text, and "" is a routing text where null is a crash.
        if (description == null) description = "";
    }

    // @JsonIgnore is load-bearing, as it is on FlowGraph: faced with several record constructors,
    // Jackson can pick this one to deserialise with and silently drop the components it predates.
    /** The pre-link shape, kept so the many existing constructions stay valid. */
    @JsonIgnore
    public LibraryAgent(String id, String name, String model, String effort,
                        long maxTokens, String systemPrompt) {
        this(id, name, model, effort, maxTokens, systemPrompt, "", 1);
    }
}
