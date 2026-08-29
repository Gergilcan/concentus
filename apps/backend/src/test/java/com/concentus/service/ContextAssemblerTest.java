package com.concentus.service;

import com.concentus.config.Settings;
import com.concentus.support.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the three decisions between a ranked list and a prompt: merge, order, budget. */
class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler(12_000);

    private static KnowledgeRetriever.Hit hit(String doc, int seq, String content, double score) {
        return new KnowledgeRetriever.Hit(doc, seq, content, score);
    }

    /** An assembler whose installation has these settings and nothing else. */
    private static ContextAssembler with(Map<String, String> settings) {
        return new ContextAssembler(Settings.of(settings), 12_000, 0);
    }

    @Test
    void adjacentPassagesBecomeOneSpanWithoutRepeatingTheOverlap() {
        // Chunks overlap at ingest, so consecutive hits share their seam. Passed through as two
        // passages, the repeated sentence reads as emphasis and costs the budget twice.
        String tail = "El plazo es de treinta dias.";
        var assembled = assembler.assemble(List.of(
                hit("a.txt", 0, "Primera parte del documento. " + tail, 0.9),
                hit("a.txt", 1, tail + " Segunda parte del documento.", 0.8)));

        assertThat(assembled.passages()).singleElement().satisfies(p -> {
            assertThat(p.firstSeq()).isZero();
            assertThat(p.lastSeq()).isEqualTo(1);
            assertThat(p.content()).containsOnlyOnce(tail);
            assertThat(p.citation()).isEqualTo("a.txt — passages 1–2");
        });
    }

    @Test
    void nonAdjacentPassagesFromOneDocumentStaySeparate() {
        var assembled = assembler.assemble(List.of(
                hit("a.txt", 0, "Principio.", 0.9),
                hit("a.txt", 7, "Mucho despues.", 0.8)));

        assertThat(assembled.passages()).hasSize(2);
        assertThat(assembled.passages().get(0).citation()).isEqualTo("a.txt — passage 1");
        assertThat(assembled.passages().get(1).citation()).isEqualTo("a.txt — passage 8");
    }

    @Test
    void passagesAreOrderedByPositionNotByScore() {
        // A document is written to be read forwards. Score order hands the model step 4, then
        // step 1, then step 7 and asks it to explain the process.
        var assembled = assembler.assemble(List.of(
                hit("a.txt", 5, "Quinto.", 0.99),
                hit("a.txt", 1, "Primero.", 0.10),
                hit("a.txt", 3, "Tercero.", 0.50)));

        assertThat(assembled.passages()).extracting(ContextAssembler.Passage::firstSeq)
                .containsExactly(1, 3, 5);
    }

    @Test
    void theBudgetDropsTheLeastRelevantNotTheEndOfTheDocument() {
        var small = new ContextAssembler(1000);
        String big = "x".repeat(600);
        var assembled = small.assemble(List.of(
                hit("a.txt", 0, big, 0.9),   // kept: most relevant
                hit("a.txt", 1, big, 0.1))); // dropped: over budget

        assertThat(assembled.truncated()).isTrue();
        assertThat(assembled.passages()).singleElement()
                .satisfies(p -> assertThat(p.firstSeq()).isZero());
    }

    @Test
    void asinglePassageOverBudgetIsKeptRatherThanReturningNothing() {
        var tiny = new ContextAssembler(1000);
        var assembled = tiny.assemble(List.of(hit("a.txt", 0, "y".repeat(5000), 0.9)));

        assertThat(assembled.passages()).hasSize(1);
    }

    // ---------------------------------------------------------------- the budget's unit

    @Test
    void withNothingSetTheBudgetIsInCharactersAsItAlwaysWas() {
        // An installation that never touched the token setting must not change behaviour.
        var assembled = with(Map.of()).assemble(List.of(hit("a.txt", 0, "Hola.", 0.9)));

        assertThat(assembled.budget().unit()).isEqualTo(ContextAssembler.Unit.CHARS);
        assertThat(assembled.budget().amount()).isEqualTo(12_000);
    }

    @Test
    void aTokenBudgetTakesPrecedenceOverTheCharacterOne() {
        // 300 tokens is about 1,200 characters of prose; a character ceiling of 100,000 that
        // nobody looked at must not overrule the number the person actually typed.
        var assembler = with(Map.of("knowledge.context-tokens", "300",
                "knowledge.context-chars", "100000"));
        String passage = "palabra ".repeat(100);   // ~800 chars, ~200 tokens
        var assembled = assembler.assemble(List.of(
                hit("a.txt", 0, passage, 0.9),   // fits
                hit("a.txt", 5, passage, 0.5),   // would exceed 300 tokens
                hit("a.txt", 9, passage, 0.1)));

        assertThat(assembled.budget().unit()).isEqualTo(ContextAssembler.Unit.TOKENS);
        assertThat(assembled.budget().amount()).isEqualTo(300);
        assertThat(assembled.truncated()).isTrue();
        assertThat(assembled.passages()).singleElement()
                .satisfies(p -> assertThat(p.firstSeq()).isZero());
    }

    @Test
    void theTokenBudgetIsEnforcedWithTheEstimatorNotWithCharacters() {
        // Dense code costs more tokens per character than prose. Two passages that fit a
        // 4-chars-per-token reading of the budget must be cut when the estimator says they do
        // not — otherwise "tokens" would be characters divided by four with a different label.
        String code = "a=b(c[d]);e={f:g,h:i};j+=k*l/m;n<o>p;q!=r;".repeat(30);   // 1,230 chars
        int perPassage = TokenEstimator.estimate(code);
        var assembler = with(Map.of("knowledge.context-tokens", String.valueOf(perPassage + 10)));

        var assembled = assembler.assemble(List.of(
                hit("a.txt", 0, code, 0.9),
                hit("a.txt", 5, code, 0.5)));

        assertThat(perPassage).isGreaterThan(code.length() / 4);
        assertThat(assembled.passages()).hasSize(1);
        assertThat(assembled.truncated()).isTrue();
    }

    @Test
    void aTokenBudgetOfZeroMeansCharacters() {
        var assembled = with(Map.of("knowledge.context-tokens", "0",
                "knowledge.context-chars", "5000")).assemble(List.of(hit("a.txt", 0, "Hola.", 0.9)));

        assertThat(assembled.budget()).isEqualTo(
                new ContextAssembler.Budget(ContextAssembler.Unit.CHARS, 5000));
    }

    @Test
    void anAbsurdlySmallTokenBudgetIsRaisedToOnePassagesWorth() {
        var assembled = with(Map.of("knowledge.context-tokens", "3"))
                .assemble(List.of(hit("a.txt", 0, "Hola.", 0.9)));

        assertThat(assembled.budget().amount()).isEqualTo(250);
    }

    @Test
    void theSizeLineReportsCharactersTokensAndTheUnitEnforced() {
        var budget = new ContextAssembler.Budget(ContextAssembler.Unit.TOKENS, 3000);
        String text = "palabra ".repeat(500);   // 4,000 chars

        String line = ContextAssembler.describeSize(text, budget);

        assertThat(line).startsWith("4,000 chars, ~");
        assertThat(line).contains("tokens estimated");
        assertThat(line).endsWith("budget 3,000 tokens");
    }

    @Test
    void promptTextNumbersEverySpanAndRepeatsTheKey() {
        var assembled = assembler.assemble(List.of(
                hit("manual.pdf", 0, "Solicitar acceso en el portal.", 0.9),
                hit("politica.docx", 2, "Revisar accesos cada trimestre.", 0.8)));

        String text = assembler.asPromptText("Documentacion", assembled);

        assertThat(text).contains("[1] manual.pdf — passage 1");
        assertThat(text).contains("[2] politica.docx — passage 3");
        // The instruction lives with the numbers it refers to; split apart, they drift.
        assertThat(text).contains("cite it with its number");
    }

    @Test
    void overlapIsFoundRatherThanAssumed() {
        // Cutting a fixed number of characters would remove real text from a passage whose
        // predecessor did not actually end that way.
        assertThat(ContextAssembler.withoutOverlap("abcdefghij", "klmnopqrst")).isEqualTo("klmnopqrst");
    }

    @Test
    void nothingRetrievedIsSaidPlainly() {
        var assembled = assembler.assemble(List.of());

        assertThat(assembled.isEmpty()).isTrue();
        assertThat(assembler.asPromptText("Base", assembled)).isEqualTo("(no matching passages)");
    }
}
