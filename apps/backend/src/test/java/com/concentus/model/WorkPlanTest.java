package com.concentus.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan validation — the gate between "a model wrote some JSON" and "N processes run in
 * parallel on this machine". Every rule here exists because the failure it prevents is silent:
 * duplicate ids merge two boxes, overlapping files corrupt work concurrently, dependsOn would
 * be quietly ignored.
 */
class WorkPlanTest {

    private static WorkPlan.WorkItem item(String id, String prompt, List<String> files) {
        return new WorkPlan.WorkItem(id, null, prompt, null, files, null, null, null, null, null);
    }

    @Test
    void aSoundPlanHasNoProblems() {
        WorkPlan plan = new WorkPlan("goal", List.of(
                item("a", "do a", List.of("src/A.java")),
                item("b", "do b", List.of("src/B.java"))));

        assertThat(plan.problems(8)).isEmpty();
    }

    @Test
    void anEmptyPlanIsRejected() {
        assertThat(new WorkPlan("goal", List.of()).problems(8))
                .anySatisfy(p -> assertThat(p).contains("no work items"));
        assertThat(new WorkPlan("goal", null).problems(8)).isNotEmpty();
    }

    @Test
    void duplicateIdsAndMissingPromptsAreEachNamed() {
        WorkPlan plan = new WorkPlan("goal", List.of(
                item("a", "do a", null),
                item("a", "", null)));

        List<String> problems = plan.problems(8);

        assertThat(problems).anySatisfy(p -> assertThat(p).contains("'a'").contains("more than once"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("no prompt"));
    }

    @Test
    void twoItemsDeclaringTheSameFileRejectThePlan() {
        // Windows and Unix spellings of one path must collide the way they would on disk.
        WorkPlan plan = new WorkPlan("goal", List.of(
                item("a", "do a", List.of("src\\Main.java")),
                item("b", "do b", List.of("src/main.JAVA"))));

        assertThat(plan.problems(8)).anySatisfy(p ->
                assertThat(p).contains("'a'").contains("'b'").contains("same file"));
    }

    @Test
    void theSameItemMayListItsOwnFileTwice() {
        WorkPlan plan = new WorkPlan("goal", List.of(
                item("a", "do a", List.of("src/A.java", "src/A.java"))));

        assertThat(plan.problems(8)).isEmpty();
    }

    @Test
    void tooManyItemsAndDependenciesAreRejected() {
        WorkPlan big = new WorkPlan("goal", List.of(
                item("a", "x", null), item("b", "x", null), item("c", "x", null)));
        assertThat(big.problems(2)).anySatisfy(p -> assertThat(p).contains("Too many"));

        WorkPlan deps = new WorkPlan("goal", List.of(new WorkPlan.WorkItem(
                "a", null, "x", null, null, null, null, null, null, List.of("b"))));
        assertThat(deps.problems(8)).anySatisfy(p -> assertThat(p).contains("dependsOn"));
    }

    @Test
    void allProblemsComeBackInOnePassNotOneAtATime() {
        WorkPlan plan = new WorkPlan("goal", List.of(
                item("a", "", List.of("f")),
                item("a", "ok", null),
                item("b", "ok", List.of("f")),
                new WorkPlan.WorkItem("c", null, "x", null, null, null, null, null, null, List.of("a"))));

        // Missing prompt, duplicate id, shared file across ids, dependsOn: four problems, one reply.
        assertThat(plan.problems(8)).hasSizeGreaterThanOrEqualTo(4);
    }
}
