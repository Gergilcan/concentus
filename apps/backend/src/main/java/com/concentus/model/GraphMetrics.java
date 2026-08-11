package com.concentus.model;

/**
 * Health of a fan-out run as a graph, not a transcript: how parallel it really was, how much
 * retrying propped it up, and how hard the verifier worked. Null (absent from the report) for
 * runs that never fanned out — a single-session flow has no graph to measure.
 *
 * @param workers         worker processes this run executed (drawn or plan-born)
 * @param workersFailed   of those, how many ended failed (spawn errors, timeouts, bad exits)
 * @param workersRejected outputs the verifier killed — they finished, and did not survive
 * @param retries         extra process launches across all nodes; a run that only passes after
 *                        retrying every worker is not healthy, just lucky
 * @param verdicts        worker outputs judged by the verifier (0 = no verifier ran)
 * @param wallMs          first worker start to last worker end — what the fan-out actually took
 * @param sumWorkerMs     the same work end to end — what it WOULD have taken sequentially;
 *                        sum/wall is the parallelism the graph really bought
 */
public record GraphMetrics(int workers, int workersFailed, int workersRejected,
                           int retries, int verdicts, long wallMs, long sumWorkerMs) {
}
