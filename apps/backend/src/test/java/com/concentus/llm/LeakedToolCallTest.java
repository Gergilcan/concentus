package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rescuing a tool call a model wrote into its message text instead of into {@code tool_calls}.
 *
 * <p>From a real run: Qwen answered
 * {@code {"name": "respond", "arguments": {"message": "No tengo acceso…"}}} as plain content. There
 * is no {@code respond} tool — the model had decided its own final answer also had to be a tool
 * call. Left alone, a person reads raw JSON and any genuine tool call in that shape never runs at
 * all, which looks like a model refusing to work rather than one whose output was mis-fielded.
 */
class LeakedToolCallTest {

    private static final List<ChatTypes.ToolSpec> OFFERED = List.of(
            new ChatTypes.ToolSpec("list_contacts", "", new ObjectMapper().createObjectNode()),
            new ChatTypes.ToolSpec("search_holded_tools", "", new ObjectMapper().createObjectNode()));

    @Test
    void aRealToolCallInTheWrongFieldIsPromoted() {
        var leaked = LocalModelClient.recoverLeakedCall(
                "{\"name\": \"list_contacts\", \"arguments\": {\"limit\": 10}}", OFFERED);

        assertThat(leaked).isNotNull();
        assertThat(leaked.call().name()).isEqualTo("list_contacts");
        assertThat(leaked.call().argumentsJson()).contains("\"limit\":10");
        // The JSON must not also reach the user as prose.
        assertThat(leaked.text()).isNull();
    }

    @Test
    void anInventedWrapperIsUnwrappedToItsMessage() {
        var leaked = LocalModelClient.recoverLeakedCall(
                "{\"name\": \"respond\", \"arguments\": {\"message\": \"No tengo acceso.\"}}", OFFERED);

        assertThat(leaked).isNotNull();
        assertThat(leaked.call()).isNull();
        assertThat(leaked.text()).isEqualTo("No tengo acceso.");
    }

    @Test
    void theWrapperFieldNameIsNotAssumed() {
        // Templates call it message, response, text or content; guessing adds one more thing to
        // get wrong, so the longest string wins.
        assertThat(LocalModelClient.recoverLeakedCall(
                "{\"name\":\"final\",\"arguments\":{\"kind\":\"x\",\"response\":\"the real answer\"}}",
                OFFERED).text()).isEqualTo("the real answer");
    }

    @Test
    void aToolCallWrappedInTagsIsAlsoRecovered() {
        var leaked = LocalModelClient.recoverLeakedCall(
                "<tool_call>{\"name\":\"list_contacts\",\"arguments\":{}}</tool_call>", OFFERED);

        assertThat(leaked.call().name()).isEqualTo("list_contacts");
    }

    @Test
    void ordinaryProseIsLeftAlone() {
        assertThat(LocalModelClient.recoverLeakedCall("Here are your contacts.", OFFERED)).isNull();
    }

    @Test
    void proseThatMerelyQuotesAToolCallIsNotHijacked() {
        // Only a message that IS the JSON counts; explaining a call is a legitimate answer.
        assertThat(LocalModelClient.recoverLeakedCall(
                "You could call {\"name\":\"list_contacts\",\"arguments\":{}} for that.", OFFERED))
                .isNull();
    }

    @Test
    void jsonThatIsNotAToolCallIsLeftAlone() {
        assertThat(LocalModelClient.recoverLeakedCall("{\"total\": 42}", OFFERED)).isNull();
    }

    @Test
    void anInventedWrapperWithNoTextIsLeftAlone() {
        // Nothing to rescue; returning an empty answer would be worse than showing what came back.
        assertThat(LocalModelClient.recoverLeakedCall(
                "{\"name\":\"respond\",\"arguments\":{\"count\":3}}", OFFERED)).isNull();
    }

    @Test
    void malformedJsonIsLeftAlone() {
        assertThat(LocalModelClient.recoverLeakedCall("{\"name\": \"list_contacts\"", OFFERED)).isNull();
    }

    @Test
    void argumentsSentAsAStringStillWork() {
        var leaked = LocalModelClient.recoverLeakedCall(
                "{\"name\":\"list_contacts\",\"arguments\":\"{\\\"limit\\\":5}\"}", OFFERED);

        assertThat(leaked.call().argumentsJson()).isEqualTo("{\"limit\":5}");
    }
}
