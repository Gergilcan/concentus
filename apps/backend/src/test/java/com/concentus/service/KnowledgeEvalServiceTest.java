package com.concentus.service;

import com.concentus.integration.content.AttachmentExtractionService;
import com.concentus.integration.content.AttachmentPolicy;
import com.concentus.integration.content.PlainTextExtractor;
import com.concentus.llm.BuiltInEmbedder;
import com.concentus.llm.BuiltInReranker;
import com.concentus.llm.LocalModelClient;
import com.concentus.store.TestDatabase;
import com.concentus.telemetry.Telemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the evaluation harness: the arithmetic of the three numbers, and that a case survives
 * the document it points at going away.
 *
 * <p>The metrics are tested against retrieval that really runs, not a stub. A harness whose numbers
 * came from a mocked retriever would measure the mock.
 */
class KnowledgeEvalServiceTest {

    private final LocalModelClient models = mock(LocalModelClient.class);
    private final BuiltInEmbedder builtIn = mock(BuiltInEmbedder.class);
    private final BuiltInReranker reranker = mock(BuiltInReranker.class);
    private KnowledgeEvalService evals;
    private KnowledgeService ingest;

    @BeforeEach
    void setUp() {
        TestDatabase.jdbc().update("delete from knowledge_chunks");
        TestDatabase.jdbc().update("delete from knowledge_evals");
        when(builtIn.state()).thenReturn(BuiltInEmbedder.State.NOT_DOWNLOADED);
        when(builtIn.isReady()).thenReturn(false);
        when(reranker.isReady()).thenReturn(false);
        when(models.isConfigured()).thenReturn(false);

        var retriever = new KnowledgeRetriever(TestDatabase.jdbc(), models, builtIn, reranker,
                new ObjectMapper(), Telemetry.none(), "bge-m3");
        var extraction = new AttachmentExtractionService(
                List.of(new PlainTextExtractor()), new AttachmentPolicy(10_485_760, 31_457_280, 15));
        ingest = new KnowledgeService(TestDatabase.jdbc(), extraction, retriever, models, builtIn,
                reranker, new ObjectMapper(), "bge-m3");
        evals = new KnowledgeEvalService(TestDatabase.jdbc(), retriever, new ObjectMapper());
    }

    private void put(String doc, String text) {
        ingest.ingest("kb1", doc, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCaseThatRetrievesItsDocumentFirstScoresPerfectly() {
        put("reembolsos.txt", "Los reembolsos se procesan en treinta dias desde la devolucion.");
        put("comedor.txt", "El comedor cierra a las cuatro los viernes.");
        evals.save("kb1", "cuanto tardan los reembolsos", List.of("reembolsos.txt"));

        var run = evals.run("kb1", 5, false);

        assertThat(run.cases()).isEqualTo(1);
        assertThat(run.hitRate()).isEqualTo(1.0);
        assertThat(run.recall()).isEqualTo(1.0);
        assertThat(run.mrr()).isEqualTo(1.0);
        assertThat(run.results()).singleElement().satisfies(r -> {
            assertThat(r.rank()).isEqualTo(1);
            assertThat(r.hit()).isTrue();
        });
    }

    @Test
    void aCaseWhoseDocumentNeverComesBackScoresZeroAndSaysWhatDid() {
        put("comedor.txt", "El comedor cierra a las cuatro los viernes.");
        // The expected document is not even in the base: the hardest miss, and the one whose
        // result has to be readable rather than merely false.
        evals.save("kb1", "politica de reembolsos", List.of("reembolsos.txt"));

        var run = evals.run("kb1", 5, false);

        assertThat(run.hitRate()).isZero();
        assertThat(run.mrr()).isZero();
        assertThat(run.results()).singleElement().satisfies(r -> {
            assertThat(r.rank()).isZero();
            assertThat(r.found()).isEmpty();
            // What DID come back is the explanation of the miss, so it travels with the result.
            assertThat(r.returned()).doesNotContain("reembolsos.txt");
        });
    }

    @Test
    void recallSeesAPartialAnswerThatHitRateWouldHide() {
        put("parte-uno.txt", "El procedimiento de acceso empieza con la solicitud en el portal.");
        put("parte-dos.txt", "La revision trimestral de accesos la firma el responsable.");
        put("ruido.txt", "El comedor cierra a las cuatro.");
        // One question, two documents that together answer it.
        evals.save("kb1", "procedimiento de acceso", List.of("parte-uno.txt", "parte-dos.txt"));

        // Only one document may come back, so the case "hits" while half the answer is missing.
        var run = evals.run("kb1", 1, false);

        assertThat(run.hitRate()).isEqualTo(1.0);
        assertThat(run.recall()).isEqualTo(0.5);
    }

    @Test
    void reciprocalRankSeparatesFoundItFromFoundItFirst() {
        put("primero.txt", "acceso acceso acceso portal solicitud tramite");
        put("segundo.txt", "acceso");
        evals.save("kb1", "acceso", List.of("segundo.txt"));

        var run = evals.run("kb1", 5, false);

        // Found, but not first: the score has to reflect that a person had to scroll past a
        // wrong answer, because in a run those wrong passages spend the context budget.
        assertThat(run.hitRate()).isEqualTo(1.0);
        assertThat(run.mrr()).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void aDocumentThatGoesAwayLeavesItsQuestionBehind() {
        put("reembolsos.txt", "Los reembolsos tardan treinta dias.");
        evals.save("kb1", "cuanto tardan los reembolsos", List.of("reembolsos.txt"));

        ingest.deleteDocument("kb1", "reembolsos.txt");

        // Deleting the document must NOT quietly delete the question it answered: either the
        // document went by mistake or the case needs a new answer, and both want a person.
        assertThat(evals.list("kb1")).hasSize(1);
        assertThat(evals.run("kb1", 5, false).hitRate()).isZero();
    }

    @Test
    void aCaseWithNoExpectedDocumentIsRefused() {
        assertThatThrownBy(() -> evals.save("kb1", "una pregunta", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one document");
    }

    @Test
    void anEmptyQuestionIsRefused() {
        assertThatThrownBy(() -> evals.save("kb1", "   ", List.of("a.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyGoldenSetReportsZeroCasesRatherThanDividingByThem() {
        var run = evals.run("kb1", 5, false);

        assertThat(run.cases()).isZero();
        assertThat(run.hitRate()).isZero();
        assertThat(run.mrr()).isZero();
        assertThat(run.results()).isEmpty();
    }

    @Test
    void twoPassagesOfOneDocumentOccupyOneRank() {
        // A person opens the document once. Counting its passages separately would push a
        // competing document down the ranking for no reason a reader would recognise.
        put("largo.txt", "Los accesos se solicitan en el portal.\n\nLos accesos se revisan cada "
                + "trimestre.\n\nLos accesos caducan al año.");
        put("otro.txt", "El comedor cierra a las cuatro.");
        evals.save("kb1", "accesos", List.of("largo.txt"));

        var run = evals.run("kb1", 5, false);

        assertThat(run.results()).singleElement().satisfies(r ->
                assertThat(r.returned()).doesNotHaveDuplicates());
    }

    @Test
    void deletingABaseTakesItsGoldenSetWithIt() {
        evals.save("kb1", "una pregunta", List.of("a.txt"));

        evals.deleteAll("kb1");

        assertThat(evals.list("kb1")).isEmpty();
    }
}
