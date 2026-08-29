package com.concentus.model;

/**
 * A connection on the canvas. Direction is used for delegation (coordinator -> subagent);
 * capability attachments (mcp/repo -> agent) are treated as undirected.
 *
 * @param sourceHandle which of the source block's outputs this wire leaves from, or null for the
 *                     main one. A block now has two: the one that carries what it produced, and a
 *                     second for the other way things can go — a failure, or the branch of a
 *                     condition that was not taken. Null is the main output and is what every
 *                     edge drawn before this existed means, which is why it is the absent value
 *                     rather than a name: a stored flow does not change meaning by being read by
 *                     a newer version.
 */
public record FlowEdge(String id, String source, String target, String sourceHandle) {

    /** An edge on the main output, which is most of them. */
    public FlowEdge(String id, String source, String target) {
        this(id, source, target, null);
    }

    /** The name a block's alternative output goes by, on the wire and in the graph. */
    public static final String ERROR = "error";
    /** A condition's two outputs. The main one is "it held"; this is the branch for when it did not. */
    public static final String ELSE = "else";
    /**
     * The verifier's own second output: what it rejected. Distinct from {@link #ERROR} on purpose —
     * the verifier process dying and the verifier refusing a worker's output are two facts, and
     * the boxes already keep them apart (a rejected worker DID finish its work).
     */
    public static final String REJECTED = "rejected";

    /**
     * Whether this wire leaves from the block's main output — what it produced, on success.
     *
     * <p>NOT isMain(). A bean-style getter on a record becomes a JSON property: Jackson wrote a
     * "main" field on every edge it serialised and then refused to read its own output back,
     * which took every stored flow with it. The same shape as goldenAutoRunOrDefault in FlowGraph,
     * and the second time this has cost a release.
     */
    public boolean onMainOutput() {
        return sourceHandle == null || sourceHandle.isBlank();
    }

    public boolean is(String handle) {
        return handle.equals(sourceHandle);
    }
}
