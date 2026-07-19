package com.concentus.llm;

/** A provider call failed. Carries the provider id so the run console can say who rejected it. */
public class LlmException extends RuntimeException {

    private final String providerId;

    public LlmException(String providerId, String message) {
        super(message);
        this.providerId = providerId;
    }

    public LlmException(String providerId, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
    }

    public String providerId() {
        return providerId;
    }
}
