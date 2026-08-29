package com.concentus.model;

import java.util.List;

/**
 * One evaluation of a flow: every case run against one revision, judged, and counted.
 *
 * <p>{@code flowVersion} is the headline. Two results on different versions are the whole point
 * of keeping them — "v7 8/10 → v8 10/10" is what an edit did, stated in a form that can be
 * argued with.
 *
 * <p>{@code status} is {@code running} while cases are still being run and {@code done} after;
 * {@code cases} grows as each one is judged, so a long evaluation can be watched rather than
 * waited for. {@code finishedAt} is null until it is done.
 */
public record FlowEvalResult(String id, String flowId, int flowVersion, long startedAt,
                             Long finishedAt, String status, List<FlowEvalCaseResult> cases,
                             int passed, int total) {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
}
