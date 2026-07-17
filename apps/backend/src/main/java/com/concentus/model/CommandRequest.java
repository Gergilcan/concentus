package com.concentus.model;

/** Body for sending an explicit instruction to a running session. */
public record CommandRequest(String text) {
}
