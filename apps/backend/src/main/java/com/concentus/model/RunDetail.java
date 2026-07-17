package com.concentus.model;

import java.util.List;

/** A run's summary plus its buffered output (for initial load before the WebSocket attaches). */
public record RunDetail(RunSummary run, List<RunEvent> events) {
}
