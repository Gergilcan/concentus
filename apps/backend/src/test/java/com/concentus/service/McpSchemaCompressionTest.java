package com.concentus.service;

import com.concentus.llm.ChatTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trimming a tool definition down to what a model needs in order to call it.
 *
 * <p>Free savings that compound with the allowlist: real schemas carry paragraphs of description
 * and enums with hundreds of members, and every character is prompt. What decides whether a model
 * calls a tool correctly is the name, the parameter names and their types — not the third
 * paragraph of prose.
 *
 * <p>The invariant that matters is that this only ever <em>removes</em>. A parameter dropped by
 * accident would make a tool uncallable in a way nobody would trace back to here.
 */
class McpSchemaCompressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatTypes.ToolSpec compress(String description, ObjectNode schema) {
        return LocalModelAgentExecutor.compress(new ChatTypes.ToolSpec("t", description, schema));
    }

    @Test
    void aLongDescriptionIsCutAndMarked() {
        String longText = "x".repeat(500);

        ChatTypes.ToolSpec out = compress(longText, MAPPER.createObjectNode());

        assertThat(out.description()).hasSizeLessThan(300).endsWith("…");
    }

    @Test
    void aShortDescriptionIsUntouched() {
        assertThat(compress("Creates a contact.", MAPPER.createObjectNode()).description())
                .isEqualTo("Creates a contact.");
    }

    @Test
    void parametersSurviveCompressionIntact() {
        // The one thing that must never be lost: a dropped parameter makes the tool uncallable in
        // a way nobody would trace back to here.
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("name").put("type", "string");
        props.putObject("amount").put("type", "number");
        schema.putArray("required").add("name");

        ChatTypes.ToolSpec out = compress("d", schema);

        assertThat(out.parameters().path("properties").path("name").path("type").asText())
                .isEqualTo("string");
        assertThat(out.parameters().path("properties").path("amount").path("type").asText())
                .isEqualTo("number");
        assertThat(out.parameters().path("required").get(0).asText()).isEqualTo("name");
    }

    @Test
    void aHugeEnumIsCappedAndTheRemainderIsStated() {
        ObjectNode schema = MAPPER.createObjectNode();
        ObjectNode props = schema.putObject("properties");
        ObjectNode currency = props.putObject("currency");
        ArrayNode values = currency.putArray("enum");
        for (int i = 0; i < 180; i++) values.add("CUR" + i);

        ChatTypes.ToolSpec out = compress("d", schema);
        var capped = out.parameters().path("properties").path("currency");

        assertThat(capped.path("enum")).hasSize(12);
        // Said rather than silently shortened, so the model knows the list continues.
        assertThat(capped.path("description").asText()).contains("180 values");
    }

    @Test
    void aShortEnumIsLeftAlone() {
        ObjectNode schema = MAPPER.createObjectNode();
        ObjectNode status = schema.putObject("properties").putObject("status");
        status.putArray("enum").add("draft").add("sent");

        assertThat(compress("d", schema).parameters()
                .path("properties").path("status").path("enum")).hasSize(2);
    }

    @Test
    void examplesAreDroppedBecauseTheyAreForHumans() {
        ObjectNode schema = MAPPER.createObjectNode();
        ObjectNode name = schema.putObject("properties").putObject("name");
        name.put("type", "string");
        name.putArray("examples").add("Acme SL");

        assertThat(compress("d", schema).parameters()
                .path("properties").path("name").has("examples")).isFalse();
    }

    @Test
    void nestedObjectsAndArrayItemsAreTrimmedToo() {
        ObjectNode schema = MAPPER.createObjectNode();
        ObjectNode lines = schema.putObject("properties").putObject("lines");
        ObjectNode items = lines.putObject("items");
        items.putObject("properties").putObject("sku").put("description", "y".repeat(400));

        ChatTypes.ToolSpec out = compress("d", schema);

        assertThat(out.parameters().path("properties").path("lines").path("items")
                .path("properties").path("sku").path("description").asText())
                .hasSizeLessThan(300);
    }

    @Test
    void theOriginalSchemaIsNotMutated() {
        // Tool lists are cached per run; mutating in place would compound the trim on every turn
        // until the schema was unusable.
        ObjectNode schema = MAPPER.createObjectNode();
        schema.putObject("properties").putObject("name").put("description", "z".repeat(400));

        compress("d", schema);

        assertThat(schema.path("properties").path("name").path("description").asText())
                .hasSize(400);
    }

    @Test
    void aNullDescriptionOrSchemaDoesNotThrow() {
        assertThat(LocalModelAgentExecutor.compress(new ChatTypes.ToolSpec("t", null, null))
                .description()).isEmpty();
    }
}
