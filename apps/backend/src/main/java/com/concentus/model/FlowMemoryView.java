package com.concentus.model;

import com.concentus.store.FlowMemoryStore;

import java.util.List;

/**
 * A flow's memory as the UI reads it: whether persistence is up at all, how many notes exist, and
 * the newest ones (newest first — the order a person checks them in, unlike the chronological
 * order agents read).
 */
public record FlowMemoryView(boolean available, int count, List<FlowMemoryStore.Note> notes) {
}
