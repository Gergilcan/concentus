package com.concentus.service;

import com.concentus.integration.content.AttachmentExtractionService;
import com.concentus.integration.content.AttachmentPolicy;
import com.concentus.integration.content.PlainTextExtractor;
import com.concentus.llm.LocalModelClient;
import com.concentus.store.TestDatabase;
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
 * Tests for {@link KnowledgeService} against a real embedded PostgreSQL: ingest round-trips,
 * re-upload replaces rather than accumulates, folders delete what they should, and the status line
 * names whichever piece is missing. Ranking moved out with the retriever and is covered by
 * {@link KnowledgeRetrieverTest}.
 */
class KnowledgeServiceTest {

    private final LocalModelClient models = mock(LocalModelClient.class);
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        TestDatabase.jdbc().update("delete from knowledge_chunks");
        // A real extractor for plain text; the heavier formats have their own suites.
        var extraction = new AttachmentExtractionService(
                List.of(new PlainTextExtractor()), new AttachmentPolicy(10_485_760, 31_457_280, 15));
        var builtIn = mock(com.concentus.llm.BuiltInEmbedder.class);
        when(builtIn.state()).thenReturn(com.concentus.llm.BuiltInEmbedder.State.NOT_DOWNLOADED);
        when(builtIn.isReady()).thenReturn(false);
        var reranker = mock(com.concentus.llm.BuiltInReranker.class);
        when(reranker.isReady()).thenReturn(false);
        // A built-in embedder that is never ready: these tests cover the model-server path, and
        // the in-process model has its own suite.
        var retriever = new KnowledgeRetriever(TestDatabase.jdbc(), models, builtIn, reranker,
                new ObjectMapper(), com.concentus.telemetry.Telemetry.none(), "bge-m3");
        service = new KnowledgeService(TestDatabase.jdbc(), extraction, webPage(), retriever, models,
                builtIn, reranker, new ObjectMapper(), "bge-m3");
    }

    /** A fetcher no test uses: every suite here ingests bytes it already holds. */
    private static com.concentus.service.WebPageFetcher webPage() {
        return new com.concentus.service.WebPageFetcher("");
    }

    @Test
    void ingestStoresChunksAndListsTheDocument() {
        when(models.isConfigured()).thenReturn(false);

        var result = service.ingest("kb1", "notes.txt",
                "Shipping to the islands takes five days.".getBytes(StandardCharsets.UTF_8));

        assertThat(result.chunks()).isEqualTo(1);
        assertThat(result.embedded()).isFalse();
        assertThat(service.documents("kb1"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.name()).isEqualTo("notes.txt");
                    assertThat(d.embedded()).isFalse();
                });
    }

    @Test
    void reUploadReplacesInsteadOfAccumulating() {
        when(models.isConfigured()).thenReturn(false);
        service.ingest("kb1", "notes.txt", "old content".getBytes(StandardCharsets.UTF_8));

        service.ingest("kb1", "notes.txt", "new content".getBytes(StandardCharsets.UTF_8));

        // Read the stored text rather than searching for it: stale chunks of a corrected document
        // answering beside the new ones is a storage bug, and it should be caught as one.
        List<String> stored = TestDatabase.jdbc().queryForList(
                "select content from knowledge_chunks where base_id = ? and doc_name = ?",
                String.class, "kb1", "notes.txt");
        assertThat(stored).singleElement().satisfies(c -> assertThat(c).contains("new content"));
    }

    @Test
    void ingestWritesTheLexemeSoKeywordSearchWorksImmediately() {
        when(models.isConfigured()).thenReturn(false);
        service.ingest("kb1", "a.txt",
                "Los accesos se revisan cada trimestre.".getBytes(StandardCharsets.UTF_8));

        Integer matching = TestDatabase.jdbc().queryForObject("""
                select count(*) from knowledge_chunks
                 where base_id = ? and lexeme @@ to_tsquery('spanish', ?)
                """, Integer.class, "kb1", "acceso");
        // Stemmed, not merely stored: the query says "acceso" and the document says "accesos".
        assertThat(matching).isEqualTo(1);
    }

    @Test
    void aDocumentIngestedWithoutEmbeddingsSaysSoRatherThanLookingFine() {
        when(models.isConfigured()).thenReturn(false);

        var result = service.ingest("kb1", "a.txt", "Contenido.".getBytes(StandardCharsets.UTF_8));

        assertThat(result.embedded()).isFalse();
        assertThat(result.detail()).contains("keyword search only");
    }

    @Test
    void aFileWithNoExtractableTextIsRejectedWithAReason() {
        assertThatThrownBy(() -> service.ingest("kb1", "empty.txt", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No text could be extracted");
    }

    @Test
    void chunkingSplitsLongTextWithOverlap() {
        String paragraph = "Lorem ipsum dolor sit amet consectetur. ".repeat(30); // ~1200 chars
        String text = paragraph + "\n\n" + paragraph + "\n\n" + paragraph;

        List<String> chunks = KnowledgeService.chunk(text);

        assertThat(chunks.size()).isGreaterThan(1);
        // Overlap: the head of a later chunk repeats the tail of the one before it.
        assertThat(chunks.get(1)).contains(chunks.get(0).substring(chunks.get(0).length() - 50));
    }

    @Test
    void statusNamesExactlyWhatIsMissing() {
        // Sin servidor configurado.
        when(models.isConfigured()).thenReturn(false);
        assertThat(service.status().semantic()).isFalse();
        assertThat(service.status().detail()).contains("Download the built-in model");

        // Configurado pero sin responder.
        when(models.isConfigured()).thenReturn(true);
        when(models.baseUrl()).thenReturn("http://localhost:11434/v1");
        when(models.listModels()).thenThrow(new RuntimeException("connection refused"));
        assertThat(service.status().semantic()).isFalse();
        assertThat(service.status().detail()).contains("not answering");
    }

    @Test
    void statusReportsSemanticOnlyWhenTheEmbeddingModelIsServed() {
        when(models.isConfigured()).thenReturn(true);

        when(models.listModels()).thenReturn(java.util.Set.of("qwen3:14b"));
        assertThat(service.status().semantic()).isFalse();
        assertThat(service.status().detail()).contains("ollama pull bge-m3");

        // El tag de versión cuenta como presente: "bge-m3:latest" ES bge-m3.
        when(models.listModels()).thenReturn(java.util.Set.of("bge-m3:latest", "qwen3:14b"));
        assertThat(service.status().semantic()).isTrue();
    }

    @Test
    void deletingAFolderTakesEverythingUnderItAndNothingBeside() {
        when(models.isConfigured()).thenReturn(false);
        service.ingest("kb1", "docs/a.txt", "contenido uno".getBytes(StandardCharsets.UTF_8));
        service.ingest("kb1", "docs/deep/b.txt", "contenido dos".getBytes(StandardCharsets.UTF_8));
        // A sibling whose name STARTS with the folder name: the classic prefix-delete casualty.
        service.ingest("kb1", "docs-archive/c.txt", "contenido tres".getBytes(StandardCharsets.UTF_8));
        service.ingest("kb1", "root.txt", "contenido cuatro".getBytes(StandardCharsets.UTF_8));

        assertThat(service.deleteFolder("kb1", "docs")).isEqualTo(2);

        assertThat(service.documents("kb1")).extracting(KnowledgeService.DocInfo::name)
                .containsExactlyInAnyOrder("docs-archive/c.txt", "root.txt");
    }

    @Test
    void aFolderNameWithSqlWildcardsMatchesItselfOnly() {
        when(models.isConfigured()).thenReturn(false);
        service.ingest("kb1", "100%_done/a.txt", "contenido equis".getBytes(StandardCharsets.UTF_8));
        service.ingest("kb1", "1000_done/b.txt", "contenido ye".getBytes(StandardCharsets.UTF_8));

        // Unescaped, '%' and '_' are LIKE wildcards and this would take the sibling too.
        assertThat(service.deleteFolder("kb1", "100%_done")).isEqualTo(1);
        assertThat(service.documents("kb1")).extracting(KnowledgeService.DocInfo::name)
                .containsExactly("1000_done/b.txt");
    }

    @Test
    void anEmptyFolderIsRefusedRatherThanDeletingTheWholeBase() {
        assertThatThrownBy(() -> service.deleteFolder("kb1", "/"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
