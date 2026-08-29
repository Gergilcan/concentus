package com.concentus.model;

/**
 * What one case did in one evaluation.
 *
 * @param runId  the execution that produced the output, so a failure can be opened and read
 *               rather than merely counted; null when no run could be started
 * @param why    the judge's reason, in one line — a verdict without a reason is a number nobody
 *               can act on
 * @param output an excerpt of the run's final answer, enough to read the verdict against without
 *               opening the run; the whole answer lives on the run itself
 */
public record FlowEvalCaseResult(String caseId, String name, String runId, boolean passed,
                                 String why, String output) {
}
