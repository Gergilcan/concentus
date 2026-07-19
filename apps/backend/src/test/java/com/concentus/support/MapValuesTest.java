package com.concentus.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the {@link MapValues} extraction helpers shared by FlowCompiler and TriggerSpec. */
class MapValuesTest {

    // ------------------------------------------------------------------- str()

    @Test
    void strReturnsThePresentValue() {
        Map<String, Object> d = Map.of("name", "Solo");

        assertThat(MapValues.str(d, "name", "fallback")).isEqualTo("Solo");
    }

    @Test
    void strReturnsFallbackWhenKeyIsMissing() {
        Map<String, Object> d = Map.of();

        assertThat(MapValues.str(d, "name", "fallback")).isEqualTo("fallback");
    }

    @Test
    void strReturnsFallbackWhenValueIsBlank() {
        Map<String, Object> d = Map.of("name", "   ");

        assertThat(MapValues.str(d, "name", "fallback")).isEqualTo("fallback");
    }

    @Test
    void strReturnsNullFallbackWhenKeyIsMissingAndFallbackIsNull() {
        Map<String, Object> d = Map.of();

        assertThat(MapValues.str(d, "name", null)).isNull();
    }

    @Test
    void strStringifiesNonStringValues() {
        Map<String, Object> d = Map.of("count", 42);

        assertThat(MapValues.str(d, "count", "fallback")).isEqualTo("42");
    }

    @Test
    void strTreatsAnExplicitNullValueAsMissing() {
        Map<String, Object> d = new HashMap<>();
        d.put("name", null);

        assertThat(MapValues.str(d, "name", "fallback")).isEqualTo("fallback");
    }

    // ------------------------------------------------------------------- lng()

    @Test
    void lngReturnsALongValueDirectly() {
        Map<String, Object> d = Map.of("maxTokens", 16000L);

        assertThat(MapValues.lng(d, "maxTokens", 1)).isEqualTo(16000L);
    }

    @Test
    void lngReturnsAnIntValueAsLong() {
        Map<String, Object> d = Map.of("maxTokens", 50);

        assertThat(MapValues.lng(d, "maxTokens", 1)).isEqualTo(50L);
    }

    @Test
    void lngParsesANumericString() {
        Map<String, Object> d = Map.of("maxTokens", "8000");

        assertThat(MapValues.lng(d, "maxTokens", 1)).isEqualTo(8000L);
    }

    @Test
    void lngParsesANumericStringWithWhitespace() {
        Map<String, Object> d = Map.of("maxTokens", "  8000  ");

        assertThat(MapValues.lng(d, "maxTokens", 1)).isEqualTo(8000L);
    }

    @Test
    void lngReturnsFallbackWhenKeyIsMissing() {
        Map<String, Object> d = Map.of();

        assertThat(MapValues.lng(d, "maxTokens", 99)).isEqualTo(99L);
    }

    @Test
    void lngReturnsFallbackWhenStringValueIsBlank() {
        Map<String, Object> d = Map.of("maxTokens", "   ");

        assertThat(MapValues.lng(d, "maxTokens", 99)).isEqualTo(99L);
    }

    @Test
    void lngReturnsFallbackWhenStringValueIsNotNumeric() {
        Map<String, Object> d = Map.of("maxTokens", "not-a-number");

        assertThat(MapValues.lng(d, "maxTokens", 99)).isEqualTo(99L);
    }

    @Test
    void lngReturnsFallbackWhenValueIsAnUnrelatedType() {
        Map<String, Object> d = Map.of("maxTokens", true);

        assertThat(MapValues.lng(d, "maxTokens", 99)).isEqualTo(99L);
    }
}
