package com.concentus.runners.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;

/** Frames to and from the wire. One mapper configuration, shared by both ends. */
public final class Frames {

    private Frames() {
    }

    public static String write(ObjectMapper mapper, Frame frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @throws IllegalArgumentException when the text is not a frame this side knows */
    public static Frame read(ObjectMapper mapper, String text) {
        try {
            return mapper.readValue(text, Frame.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Not a runner frame: " + e.getMessage(), e);
        }
    }

    /** A result record as the JSON node an {@link Frame.Ack} carries. */
    public static com.fasterxml.jackson.databind.JsonNode result(ObjectMapper mapper, Object result) {
        return result == null ? null : mapper.valueToTree(result);
    }

    /** The other way: an ack's result as the record the request expects. */
    public static <T> T result(ObjectMapper mapper, Frame.Ack ack, Class<T> type) {
        if (ack.result() == null || ack.result().isNull()) return null;
        try {
            return mapper.treeToValue(ack.result(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Unexpected result for " + ack.reqId() + ": " + e.getMessage(), e);
        }
    }
}
