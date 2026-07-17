package com.concentus.model;

/** A reusable agent definition from the YAML agent library (used to populate an agent node). */
public record LibraryAgent(String id, String name, String model, String effort,
                           long maxTokens, String systemPrompt) {
}
