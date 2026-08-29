package com.concentus.model;

/**
 * A named collection of documents an agent can retrieve from.
 *
 * <p>Distinct from a context folder, and the distinction is what to reach for when: a context
 * folder hands the agent the files themselves — right when the files ARE the work, like a
 * repository. A knowledge base is for reference material too large to hand over whole: manuals,
 * policies, past reports. Only the passages relevant to the task at hand are injected.
 *
 * @param description shown in the picker, and worth writing: it is how a person with twelve bases
 *                    remembers which one holds the shipping policies — and it is what an agent
 *                    reads to decide whether to search this base at all
 * @param minRole     the lowest role that may read this base, from the same ladder everything else
 *                    uses. Blank or absent means everybody, which is what every base created
 *                    before this field existed meant and must go on meaning
 */
public record KnowledgeDef(String id, String name, String description, String minRole) {
}
