package com.concentus.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict's validation: the rules that make the verifier's power real. Every worker judged,
 * no invented ids, no kills without a reason — a verdict that could skip a worker or reject
 * silently would let the verifier be either a rubber stamp or an unaccountable one.
 */
class WorkVerdictTest {

    private static WorkVerdict.Item item(String id, String verdict, String reason) {
        return new WorkVerdict.Item(id, verdict, reason);
    }

    @Test
    void aCompleteVerdictHasNoProblems() {
        WorkVerdict v = new WorkVerdict("one weak, one fine", List.of(
                item("n1", "accept", null),
                item("n2", "reject", "claims tests pass but wrote none")));

        assertThat(v.problems(Set.of("n1", "n2"))).isEmpty();
        assertThat(v.rejectedCount()).isEqualTo(1);
        assertThat(v.of("n2").rejected()).isTrue();
        assertThat(v.of("n1").accepted()).isTrue();
        assertThat(v.of("missing")).isNull();
    }

    @Test
    void everyProblemIsNamedInOnePass() {
        WorkVerdict v = new WorkVerdict(null, List.of(
                item("ghost", "accept", null),          // not a worker awaiting judgment
                item("n1", "maybe", null),              // verdict is not accept/reject
                item("n1", "accept", null),             // judged twice
                item("n2", "reject", " ")));            // kill without a reason

        List<String> problems = v.problems(Set.of("n1", "n2", "n3"));

        assertThat(String.join("\n", problems))
                .contains("'ghost'")
                .contains("'maybe'")
                .contains("more than once")
                .contains("without a reason")
                .contains("'n3' was not judged");
    }

    @Test
    void anEmptyVerdictIsRejectedOutright() {
        assertThat(new WorkVerdict(null, List.of()).problems(Set.of("n1")))
                .hasSize(1)
                .first().asString().contains("no items");
        assertThat(new WorkVerdict(null, null).problems(Set.of("n1")))
                .hasSize(1)
                .first().asString().contains("no items");
    }

    @Test
    void verdictWordsAreCaseInsensitiveButNothingElsePasses() {
        assertThat(item("n1", "Reject", "why").rejected()).isTrue();
        assertThat(item("n1", "ACCEPT", null).accepted()).isTrue();
        assertThat(item("n1", "approve", null).accepted()).isFalse();
        assertThat(item("n1", null, null).rejected()).isFalse();
    }
}
