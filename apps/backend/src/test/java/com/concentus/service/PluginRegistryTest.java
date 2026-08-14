package com.concentus.service;

import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run's {@code --settings} document says about plugins. The CLI is never shelled here: the
 * installed list is stubbed, which is the only part {@link PluginRegistry#settingsJsonFor} reads.
 */
class PluginRegistryTest {

    private static PluginRegistry withInstalled(PluginRegistry.PluginInfo... installed) {
        return new PluginRegistry(new LocalClaudeSupport("claude"), new ObjectMapper()) {
            @Override
            public List<PluginInfo> list() {
                return List.of(installed);
            }
        };
    }

    private static PluginRegistry.PluginInfo plugin(String id) {
        return new PluginRegistry.PluginInfo(id, "1", "user", true);
    }

    @Test
    void theSelectionIsEnabledAndEveryOtherInstalledPluginDisabled() {
        // A leftover globally-enabled plugin must not ride along into a flow that chose its set.
        String json = withInstalled(plugin("caveman@caveman"), plugin("other@mkt"))
                .settingsJsonFor(List.of("caveman@caveman"));

        assertThat(json)
                .contains("\"caveman@caveman\":true")
                .contains("\"other@mkt\":false");
    }

    @Test
    void anEmptySelectionDisablesTheLot() {
        // Not "the CLI's own defaults": a flow that ticked nothing asked for nothing, and the
        // defaults made an empty list a lie about what the run loaded.
        String json = withInstalled(plugin("caveman@caveman"), plugin("other@mkt"))
                .settingsJsonFor(List.of());

        assertThat(json)
                .contains("\"caveman@caveman\":false")
                .contains("\"other@mkt\":false");
    }

    @Test
    void withNothingInstalledThereIsNothingToSay() {
        assertThat(withInstalled().settingsJsonFor(List.of())).isNull();
        assertThat(withInstalled().settingsJsonFor(List.of("caveman@caveman"))).isNull();
    }

    @Test
    void anIdThatIsNotAPluginIdIsIgnoredRatherThanWritten() {
        // The id reaches a command line eventually; the shape is checked before it is trusted.
        String json = withInstalled(plugin("caveman@caveman"))
                .settingsJsonFor(List.of("rm -rf /", "caveman@caveman"));

        assertThat(json).doesNotContain("rm -rf").contains("\"caveman@caveman\":true");
    }
}
