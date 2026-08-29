package com.concentus.model;

import java.util.Set;

/**
 * One case of a flow's evaluation dataset: an input to run the flow with, and how to decide
 * whether what came out is right.
 *
 * <p>A golden run answers "did this edit change the output?"; it cannot answer "is the output
 * right?", because it compares two answers rather than measuring either. A case carries the
 * measure — the expectation and the judge that applies it — so a score exists per flow version,
 * and a score can go down.
 *
 * <p>{@code judge} is one of {@link #JUDGES}: {@code contains} (the expected text appears in the
 * output, ignoring case), {@code regex} (the expected pattern matches somewhere in it), {@code exact}
 * (the trimmed output IS the expected text) or {@code llm} (a model reads both and says PASS or
 * FAIL — the only one that costs a model call per case, and the only one that can judge meaning).
 */
public record FlowEvalCase(String id, String flowId, String name, String input, String expected,
                           String judge, long createdAt) {

    public static final Set<String> JUDGES = Set.of("contains", "regex", "exact", "llm");
}
