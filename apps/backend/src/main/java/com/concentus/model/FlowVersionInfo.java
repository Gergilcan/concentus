package com.concentus.model;

/** One entry in a flow's version history. */
public record FlowVersionInfo(int version, String name, long createdAt) {
}
