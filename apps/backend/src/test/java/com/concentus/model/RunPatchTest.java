package com.concentus.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review ledger's record: what the store keeps of it, and how much. The JSON round trip is
 * here as a tripwire — a record with a convenience constructor is how Jackson silently drops a
 * component, and this one is persisted with every run.
 */
class RunPatchTest {

    private static final String PATCH = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -one
            +two
            """;

    @Test
    void every_component_survives_the_json_round_trip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunPatch taken = RunPatch.registered("w1", "Worker", "repo", "https://x/repo.git",
                Path.of("C:/data/local/run/workers/w1/repo"), "abc123").taken(PATCH, 42L);

        String json = mapper.writeValueAsString(List.of(taken));
        List<RunPatch> back = mapper.readValue(json, new TypeReference<List<RunPatch>>() {});

        assertThat(back).containsExactly(taken);
        assertThat(back.get(0).stats()).isEqualTo(new PatchStats(1, 1, 1));
        assertThat(back.get(0).base()).isEqualTo("abc123");
        assertThat(back.get(0).directory()).isNotNull();
    }

    @Test
    void registered_means_a_checkout_that_has_not_been_read() {
        RunPatch p = RunPatch.registered("c", "Coordinator", "repo", "https://x/repo.git", null, null);

        assertThat(p.patch()).isNull();
        assertThat(p.stats()).isEqualTo(PatchStats.NONE);
        assertThat(p.takenAt()).isZero();
        assertThat(p.key()).isEqualTo("c/repo");
    }

    @Test
    void capping_keeps_patches_in_order_until_the_budget_is_spent_and_strips_the_rest_to_stats() {
        RunPatch small = RunPatch.registered("a", "A", "r", null, null, null).taken(PATCH, 1);
        String big = PATCH.repeat(200);
        RunPatch large = RunPatch.registered("b", "B", "r", null, null, null).taken(big, 2);
        RunPatch alsoSmall = RunPatch.registered("c", "C", "r", null, null, null).taken(PATCH, 3);
        RunPatch unread = RunPatch.registered("d", "D", "r", null, null, null);

        List<RunPatch> capped = RunPatch.capped(List.of(small, large, alsoSmall, unread),
                PATCH.length() * 2 + 10);

        assertThat(capped).hasSize(4);
        assertThat(capped.get(0)).isEqualTo(small);
        // Too big for what was left: its text goes, its numbers and identity stay, and it says why.
        assertThat(capped.get(1).patch()).isNull();
        assertThat(capped.get(1).stats()).isEqualTo(PatchStats.of(big));
        assertThat(capped.get(1).note()).isEqualTo(RunPatch.CAPPED_NOTE);
        assertThat(capped.get(1).nodeId()).isEqualTo("b");
        // Greedy, not first-failure-stops: the small one after the big one still fits.
        assertThat(capped.get(2)).isEqualTo(alsoSmall);
        assertThat(capped.get(3)).isEqualTo(unread);
    }
}
