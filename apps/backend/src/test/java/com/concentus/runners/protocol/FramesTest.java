package com.concentus.runners.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every frame survives the wire: written by one side, read by the other, equal. The protocol is
 * records on purpose, and a record that Jackson cannot rebuild is a runner that stops talking.
 */
class FramesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Frame roundTrip(Frame frame) {
        String text = Frames.write(mapper, frame);
        assertThat(text).contains("\"type\"");
        return Frames.read(mapper, text);
    }

    @Test
    void every_frame_round_trips() {
        List<Frame> frames = List.of(
                new Frame.Hello("0.1.14", "Linux", "amd64", "nas", "25", "/usr/bin/claude", true, "2.1.0",
                        "subscription", 4, "/", "/data/runs", "https://hub.example", List.of("/srv/a"), "nas"),
                new Frame.Heartbeat(2),
                new Frame.Ack("r1", true, null, Frames.result(mapper, new Frame.SyncResult("/data/runs/run_1"))),
                new Frame.Ack("r2", false, "no such directory", null),
                new Frame.Stdout("p1", "{\"type\":\"system\"}"),
                new Frame.Log("p1", "waiting for a slot"),
                new Frame.Exit("p1", 0),
                new Frame.Welcome("rn_1", "nas"),
                new Frame.WorkspaceSync("r3", "run_1", List.of(new Frame.FileEntry("CLAUDE.md", "# hi\n"))),
                new Frame.GitClone("r4", "run_1", "workers/a", List.of(new Frame.RepoEntry("https://x/y.git", "main",
                        "tok", "CONCENTUS_GIT_TOKEN_0"))),
                new Frame.GitHead("r5", "/data/runs/run_1/y"),
                new Frame.GitPatchOf("r6", "/data/runs/run_1/y"),
                new Frame.GitPatchSince("r7", "/data/runs/run_1/y", "abc"),
                new Frame.ContextResolve("r8", List.of("/srv/a", "/etc")),
                new Frame.FsRead("r9", "/srv/a/CLAUDE.md"),
                new Frame.ProcStart("r10", "p2", "run_1", List.of("claude", "-p", "hi"), "/data/runs/run_1",
                        Map.of("CONCENTUS_GIT_TOKEN_0", "tok")),
                new Frame.ProcStdin("p2", "aGk=", false),
                new Frame.ProcStdin("p2", null, true),
                new Frame.ProcStop("p2"),
                new Frame.WorkspaceDelete("r11", "run_1"));

        for (Frame frame : frames) {
            assertThat(roundTrip(frame)).as(frame.getClass().getSimpleName()).isEqualTo(frame);
        }
    }

    @Test
    void results_ride_inside_an_ack_and_come_back_typed() {
        Frame.CloneResult result = new Frame.CloneResult(List.of(
                new Frame.CheckoutEntry("https://x/y.git", "y", "/data/runs/run_1/y", "CONCENTUS_GIT_TOKEN_0",
                        "abc", true, null),
                new Frame.CheckoutEntry("https://x/z.git", null, null, null, null, false, "clone failed")));
        Frame.Ack ack = (Frame.Ack) roundTrip(new Frame.Ack("r", true, null, Frames.result(mapper, result)));

        assertThat(Frames.result(mapper, ack, Frame.CloneResult.class)).isEqualTo(result);
        assertThat(Frames.result(mapper, new Frame.Ack("r", true, null, null), Frame.CloneResult.class)).isNull();
    }

    @Test
    void a_field_the_other_side_does_not_know_is_skipped_not_refused() {
        Frame frame = Frames.read(mapper, "{\"type\":\"heartbeat\",\"busy\":1,\"future\":\"x\"}");
        assertThat(frame).isEqualTo(new Frame.Heartbeat(1));
    }

    @Test
    void something_that_is_not_a_frame_is_refused_by_name() {
        assertThatThrownBy(() -> Frames.read(mapper, "{\"type\":\"dance\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a runner frame");
        assertThatThrownBy(() -> Frames.read(mapper, "not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
