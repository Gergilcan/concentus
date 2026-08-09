package com.concentus.llm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The in-process embedding model.
 *
 * <p>The semantic assertions need the real 130 MB model, so they are skipped unless it has already
 * been downloaded into {@code CONCENTUS_TEST_MODEL_DIR}. Downloading it on every CI run would add
 * minutes and a network dependency to a suite that otherwise needs neither; what CI does verify is
 * the part that broke in practice — the state machine around the download.
 */
class BuiltInEmbedderTest {

    private static BuiltInEmbedder withModelDir(String dir) {
        return new BuiltInEmbedder(dir,
                "https://example.invalid/model.onnx", "https://example.invalid/tokenizer.json");
    }

    @Test
    void reportsNotDownloadedWhenTheModelIsAbsent(@org.junit.jupiter.api.io.TempDir Path temp) {
        BuiltInEmbedder embedder = withModelDir(temp.toString());

        assertThat(embedder.state()).isEqualTo(BuiltInEmbedder.State.NOT_DOWNLOADED);
        assertThat(embedder.isReady()).isFalse();
    }

    @Test
    void embeddingBeforeTheModelIsReadyFailsLoudlyRatherThanSilently(
            @org.junit.jupiter.api.io.TempDir Path temp) {
        BuiltInEmbedder embedder = withModelDir(temp.toString());

        // The caller catches this and falls back to word overlap; returning a zero vector instead
        // would rank every passage identically and look like a working search that finds nothing.
        assertThat(embedder.isReady()).isFalse();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> embedder.embed(List.of("hola"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void aFailedDownloadEndsInErrorWithAReason(@org.junit.jupiter.api.io.TempDir Path temp)
            throws Exception {
        BuiltInEmbedder embedder = withModelDir(temp.toString());

        embedder.download();
        for (int i = 0; i < 100 && embedder.state() == BuiltInEmbedder.State.DOWNLOADING; i++) {
            Thread.sleep(100);
        }

        assertThat(embedder.state()).isEqualTo(BuiltInEmbedder.State.ERROR);
        assertThat(embedder.error()).isNotBlank();
        // And nothing half-written is left behind to be loaded as a model next start.
        assertThat(Files.exists(temp.resolve("models/embedding/model.onnx"))).isFalse();
    }

    @Test
    void ranksBySemanticsAcrossLanguagesWhenTheModelIsPresent() {
        String dir = System.getenv("CONCENTUS_TEST_MODEL_DIR");
        assumeThat(dir).as("CONCENTUS_TEST_MODEL_DIR with a downloaded model").isNotNull();
        BuiltInEmbedder embedder = withModelDir(dir);
        assumeThat(embedder.isReady()).as("model loaded").isTrue();

        List<float[]> passages = embedder.embed(List.of(
                "Las devoluciones se procesan en un plazo de treinta dias.",
                "La cafeteria cierra a las cuatro los viernes."), false);
        float[] query = embedder.embed(
                List.of("how long until I get my money back"), true).get(0);

        // Cross-lingual: an English query over Spanish passages, where word overlap is exactly zero.
        assertThat(cosine(query, passages.get(0))).isGreaterThan(cosine(query, passages.get(1)));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // both are L2-normalised by the embedder
    }
}
