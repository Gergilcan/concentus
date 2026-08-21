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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for hybrid retrieval, against a real embedded PostgreSQL because the lexical half IS SQL.
 *
 * <p>The two tests that matter are the ones proving each branch covers the other's blind spot: an
 * exact identifier that no embedding can isolate, and a paraphrase that shares no word with the
 * document answering it. If either regresses, the whole reason for running two searches is gone
 * and nothing else here would notice.
 */
class KnowledgeRetrieverTest {

    private final LocalModelClient models = mock(LocalModelClient.class);
    private final BuiltInEmbedder builtIn = mock(BuiltInEmbedder.class);
    private final BuiltInReranker reranker = mock(BuiltInReranker.class);
    private KnowledgeRetriever retriever;
    private KnowledgeService ingest;

    @BeforeEach
    void setUp() {
        TestDatabase.jdbc().update("delete from knowledge_chunks");
        when(builtIn.state()).thenReturn(BuiltInEmbedder.State.NOT_DOWNLOADED);
        when(builtIn.isReady()).thenReturn(false);
        when(reranker.isReady()).thenReturn(false);

        retriever = new KnowledgeRetriever(TestDatabase.jdbc(), models, builtIn, reranker,
                new ObjectMapper(), Telemetry.none(), "bge-m3");
        var extraction = new AttachmentExtractionService(
                List.of(new PlainTextExtractor()), new AttachmentPolicy(10_485_760, 31_457_280, 15));
        ingest = new KnowledgeService(TestDatabase.jdbc(), extraction, retriever, models, builtIn,
                reranker, new ObjectMapper(), "bge-m3");
    }

    private void put(String doc, String text) {
        ingest.ingest("kb1", doc, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Every passage embeds to the same vector: semantics can no longer tell them apart. */
    private void embeddingsThatCannotDiscriminate() {
        when(models.isConfigured()).thenReturn(true);
        when(models.embed(anyString(), anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(1);
            return texts.stream().map(t -> new float[]{1f, 0f}).toList();
        });
    }

    @Test
    void findsAnExactIdentifierThatEmbeddingsCannotIsolate() {
        // The failure this whole slice exists for: a reference number sits in embedding space
        // beside every one of its neighbours, so a vector search returns the other policies.
        embeddingsThatCannotDiscriminate();
        put("seguridad.txt", "La norma ISO-27001 exige revisar los accesos cada trimestre.");
        put("calidad.txt", "La norma de calidad describe el proceso de auditoria interna.");
        put("rrhh.txt", "El proceso de alta de personal requiere firma del responsable.");

        var hits = retriever.search("kb1", "ISO-27001", 1);

        assertThat(hits).singleElement()
                .satisfies(h -> assertThat(h.docName()).isEqualTo("seguridad.txt"));
    }

    @Test
    void findsAParaphraseThatSharesNoWordsWithTheDocument() {
        // The mirror case: no term in common, so the lexical branch contributes nothing and the
        // answer has to come from meaning.
        when(models.isConfigured()).thenReturn(true);
        when(models.embed(anyString(), anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(1);
            return texts.stream()
                    .map(t -> t.contains("Offboarding") || t.contains("baja")
                            ? new float[]{1f, 0f}
                            : new float[]{0f, 1f})
                    .toList();
        });
        put("a.txt", "Offboarding: revocar credenciales y recuperar el equipo.");
        put("b.txt", "El comedor cierra a las cuatro los viernes.");

        var hits = retriever.search("kb1", "baja de un empleado", 1);

        assertThat(hits).singleElement()
                .satisfies(h -> assertThat(h.docName()).isEqualTo("a.txt"));
    }

    @Test
    void withNoEmbeddingModelAtAllSearchStillWorks() {
        // What used to be the word-overlap fallback. Lexical alone is strictly better, and the
        // point of the assertion is that nothing throws and results still come back.
        when(models.isConfigured()).thenReturn(false);
        put("a.txt", "Los reembolsos se procesan en treinta dias desde la devolucion.");
        put("b.txt", "El comedor cierra a las cuatro los viernes.");

        var hits = retriever.search("kb1", "cuanto tardan los reembolsos", 1);

        assertThat(hits).singleElement()
                .satisfies(h -> assertThat(h.docName()).isEqualTo("a.txt"));
    }

    @Test
    void aLongPromptStillMatches() {
        // The preload path searches with the run's entire prompt. ANDing its terms — which is what
        // plainto_tsquery would do — matches nothing at all, and the bug would look like an empty
        // knowledge base rather than a broken query.
        when(models.isConfigured()).thenReturn(false);
        put("a.txt", "Los reembolsos se procesan en treinta dias desde la devolucion.");

        String prompt = "Eres un asistente que atiende a clientes por correo. Se educado, "
                + "responde en el idioma del mensaje, y no inventes plazos. La consulta de hoy "
                + "pregunta cuanto tardan los reembolsos en llegar a la cuenta del cliente.";
        var hits = retriever.search("kb1", prompt, 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).docName()).isEqualTo("a.txt");
    }

    @Test
    void withoutARerankerResultsStillCome() {
        when(models.isConfigured()).thenReturn(false);
        when(reranker.isReady()).thenReturn(false);
        put("a.txt", "El procedimiento de solicitud de acceso empieza en el portal.");

        assertThat(retriever.search("kb1", "solicitud de acceso", 5)).isNotEmpty();
    }

    @Test
    void aRerankerThatFailsMidQueryDoesNotTakeTheSearchWithIt() {
        when(models.isConfigured()).thenReturn(false);
        when(reranker.isReady()).thenReturn(true);
        when(reranker.score(anyString(), anyList()))
                .thenThrow(new IllegalStateException("onnx exploded"));
        put("a.txt", "El procedimiento de solicitud de acceso empieza en el portal.");

        // An optional improvement must not become a single point of failure.
        assertThat(retriever.search("kb1", "solicitud de acceso", 5)).isNotEmpty();
    }

    @Test
    void therankerDecidesTheFinalOrder() {
        when(models.isConfigured()).thenReturn(false);
        put("a.txt", "El acceso al portal requiere doble factor.");
        put("b.txt", "El acceso al almacen requiere tarjeta.");

        // Scores in reverse of whatever the fusion produced: the assertion is that the reranker's
        // opinion is the one that survives, not that a particular document wins.
        when(reranker.isReady()).thenReturn(true);
        when(reranker.score(anyString(), anyList())).thenAnswer(inv -> {
            List<String> passages = inv.getArgument(1);
            float[] scores = new float[passages.size()];
            for (int i = 0; i < passages.size(); i++) {
                scores[i] = passages.get(i).contains("almacen") ? 10f : -10f;
            }
            return scores;
        });

        var hits = retriever.search("kb1", "acceso", 1);

        assertThat(hits).singleElement()
                .satisfies(h -> assertThat(h.docName()).isEqualTo("b.txt"));
    }

    @Test
    void fusionRewardsAgreementWithoutSilencingEitherBranch() {
        var semantic = List.of(
                new KnowledgeRetriever.Ranked("both.txt", 0, "both", 2, 0.9),
                new KnowledgeRetriever.Ranked("onlySemantic.txt", 0, "s", 1, 0.95));
        var lexical = List.of(
                new KnowledgeRetriever.Ranked("both.txt", 0, "both", 1, 0.5),
                new KnowledgeRetriever.Ranked("onlyLexical.txt", 0, "l", 2, 0.4));

        var fused = KnowledgeRetriever.fuse(List.of(semantic, lexical));

        // Found by both branches, so it outranks either branch's own favourite...
        assertThat(fused.get(0).doc()).isEqualTo("both.txt");
        // ...but a passage only one branch saw is still there, which is the entire point of
        // running two searches instead of trusting one.
        assertThat(fused).extracting(KnowledgeRetriever.Ranked::doc)
                .containsExactlyInAnyOrder("both.txt", "onlySemantic.txt", "onlyLexical.txt");
    }

    @Test
    void anEmptyQueryRetrievesNothingRatherThanEverything() {
        when(models.isConfigured()).thenReturn(false);
        put("a.txt", "Contenido cualquiera.");

        assertThat(retriever.search("kb1", "   ", 5)).isEmpty();
    }
}
