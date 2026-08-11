package com.concentus.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The substitution contract: what {{NAME}} becomes, and what an unknown name does NOT become. */
class VariablesTest {

    @Test
    void replacesKnownNamesAndToleratesSpacesInsideTheBraces() {
        String out = Variables.substitute(
                "Dear {{CUSTOMER}}, regards {{ COMPANY }}.",
                Map.of("CUSTOMER", "ACME", "COMPANY", "Concentus"), null);
        assertThat(out).isEqualTo("Dear ACME, regards Concentus.");
    }

    @Test
    void unknownNamesStayVerbatimAndAreReported() {
        Set<String> unresolved = new LinkedHashSet<>();
        String out = Variables.substitute("Ship to {{WAREHOUSE}} today", Map.of(), unresolved);
        assertThat(out).isEqualTo("Ship to {{WAREHOUSE}} today");
        assertThat(unresolved).containsExactly("WAREHOUSE");
    }

    @Test
    void shellSyntaxInPromptsIsLeftAlone() {
        // The reason the syntax is {{NAME}} and not ${NAME}: prompts quote shell all the time.
        String prompt = "Run `echo $HOME` and ${PATH} checks";
        assertThat(Variables.substitute(prompt, Map.of("HOME", "nope"), null)).isEqualTo(prompt);
    }

    @Test
    void replacementValuesAreNotReExpanded() {
        // A value containing brace syntax must land literally, not recurse or corrupt the regex.
        String out = Variables.substitute("{{A}}", Map.of("A", "keep {{B}} and $1 literal"), null);
        assertThat(out).isEqualTo("keep {{B}} and $1 literal");
    }

    @Test
    void nullAndPlainTextsPassThroughUntouched() {
        assertThat(Variables.substitute(null, Map.of("A", "x"), null)).isNull();
        assertThat(Variables.substitute("no refs here", Map.of("A", "x"), null))
                .isEqualTo("no refs here");
    }
}
