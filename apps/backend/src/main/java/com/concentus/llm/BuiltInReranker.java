package com.concentus.llm;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The model that decides which of the candidates actually answers the question.
 *
 * <p><b>Why a second model at all.</b> An embedding model reads the query and the passage
 * separately and compares two summaries of them; it never sees them together, which is what makes
 * it fast enough to run over a whole base. A cross-encoder reads the pair as one input and scores
 * it — it can notice that a passage mentions the right process but for the wrong department, which
 * two independent summaries cannot express. That costs a forward pass per candidate, so it is
 * hopeless over a base and exactly right over the fifty survivors of the hybrid search.
 *
 * <p>Built the same way as {@link BuiltInEmbedder}, deliberately: ONNX Runtime in-process,
 * HuggingFace's tokenizer, downloaded on demand into the app's data folder. No server, no tokens,
 * no per-query cost. The duplication between the two classes is the download-and-load lifecycle,
 * and it stays duplicated because the inference in the middle has a different shape — pairs rather
 * than texts, one logit rather than a hidden state — and a shared base class would have been an
 * abstraction over the two things these classes do <em>not</em> have in common.
 *
 * <p>The model is bge-reranker-base, quantized: XLM-RoBERTa underneath and therefore multilingual,
 * which for a user whose documents are in Spanish is not a nice-to-have. 266 MB, plus a 16 MB
 * tokenizer — a coffee-break download for a feature not everyone will want, which is why it is not
 * in the installer.
 *
 * <p><b>Absent is a state, not a failure.</b> Nothing here throws its way into a search: a base
 * with no reranker downloaded returns the fused order, which is already the better half of this
 * work. A knowledge base that stopped answering because an optional model was missing would be the
 * first place this feature broke.
 */
@Component
public class BuiltInReranker {

    private static final Logger log = LoggerFactory.getLogger(BuiltInReranker.class);

    /**
     * Fixed rather than configurable, for the reason the embedder documents: the download size
     * shown in the UI and the score interpretation below are this model's, so a knob pointing
     * elsewhere would produce a UI that lies about both.
     */
    public static final String MODEL_NAME = "bge-reranker-base";
    public static final int SIZE_MB = 282;

    private static final String MODEL_URL =
            "https://huggingface.co/Xenova/bge-reranker-base/resolve/main/onnx/model_quantized.onnx";
    private static final String TOKENIZER_URL =
            "https://huggingface.co/Xenova/bge-reranker-base/resolve/main/tokenizer.json";

    /**
     * Pairs per forward pass. Smaller than the embedder's batch: a pair is a query plus a whole
     * passage, so each row is near the 512-token ceiling rather than well under it.
     */
    private static final int BATCH = 8;

    /** Candidates worth scoring. Beyond this the fused order is already better than the tail. */
    public static final int MAX_CANDIDATES = 64;

    private final Path dir;
    private final String modelUrl;
    private final String tokenizerUrl;

    public enum State { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_DOWNLOADED);
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();
    private volatile String error = "";

    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    // Explicit, because Spring will not choose between two constructors on its own: it looks for a
    // no-arg one and fails. The same trap has cost this codebase three separate evenings.
    @org.springframework.beans.factory.annotation.Autowired
    public BuiltInReranker(@Value("${app.data-dir}") String dataDir) {
        this(dataDir, MODEL_URL, TOKENIZER_URL);
    }

    /** Visible for tests, which aim the download at an unreachable URL to exercise the failure path. */
    BuiltInReranker(String dataDir, String modelUrl, String tokenizerUrl) {
        this.dir = Path.of(dataDir).toAbsolutePath().resolve("models").resolve("reranker");
        this.modelUrl = modelUrl;
        this.tokenizerUrl = tokenizerUrl;
    }

    /** Loads an already-downloaded model off the startup thread, for the reason the embedder does. */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void loadIfPresentAsync() {
        if (!Files.isRegularFile(modelFile()) || !Files.isRegularFile(tokenizerFile())) return;
        Thread loader = new Thread(this::loadIfPresent, "reranker-model-load");
        loader.setDaemon(true);
        loader.start();
    }

    void loadIfPresent() {
        if (!Files.isRegularFile(modelFile()) || !Files.isRegularFile(tokenizerFile())) return;
        try {
            load();
        } catch (Exception e) {
            log.warn("Reranker model present but unloadable ({}); offering re-download.", e.getMessage());
            state.set(State.NOT_DOWNLOADED);
        }
    }

    private Path modelFile() {
        return dir.resolve("model.onnx");
    }

    private Path tokenizerFile() {
        return dir.resolve("tokenizer.json");
    }

    public State state() {
        return state.get();
    }

    public boolean isReady() {
        return state.get() == State.READY;
    }

    public String error() {
        return error;
    }

    public int progressPercent() {
        long total = totalBytes.get();
        return total <= 0 ? 0 : (int) (downloadedBytes.get() * 100 / total);
    }

    /** Starts the download on a background thread and returns; progress is polled, not streamed. */
    public synchronized void download() {
        if (state.get() == State.DOWNLOADING || state.get() == State.READY) return;
        state.set(State.DOWNLOADING);
        error = "";
        downloadedBytes.set(0);
        totalBytes.set(0);
        Thread worker = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                fetch(tokenizerUrl, tokenizerFile());
                fetch(modelUrl, modelFile());
                load();
                log.info("Reranker model ready at {}", dir);
            } catch (Exception e) {
                log.error("Reranker model download failed", e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                state.set(State.ERROR);
            }
        }, "reranker-model-download");
        worker.setDaemon(true);
        worker.start();
    }

    /** Streams to a temp file and renames, so a killed download never leaves half a model behind. */
    private void fetch(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        totalBytes.addAndGet(response.headers().firstValueAsLong("Content-Length").orElse(0));

        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = response.body();
             var out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                downloadedBytes.addAndGet(read);
            }
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void load() throws Exception {
        environment = OrtEnvironment.getEnvironment();
        session = environment.createSession(modelFile().toString(), new OrtSession.SessionOptions());
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile())
                // The model's positional ceiling. A pair that exceeds it is truncated from the
                // passage end, which is the right end to lose: the query leads the input.
                .optMaxLength(512)
                .optTruncation(true)
                .optPadding(true)
                .build();
        state.set(State.READY);
    }

    /** Deletes the model from disk, freeing the space; the download button comes back. */
    public synchronized void delete() throws IOException {
        closeQuietly();
        state.set(State.NOT_DOWNLOADED);
        Files.deleteIfExists(modelFile());
        Files.deleteIfExists(tokenizerFile());
    }

    /**
     * Scores each passage against the query. Higher is more relevant; the scale is the model's own
     * logit and is only meaningful for ordering, which is all any caller wants from it.
     *
     * <p>Synchronized for the reason the embedder is: the tokenizer is not documented thread-safe,
     * and two runs searching at once is the normal case rather than the exotic one.
     *
     * @throws IllegalStateException if the model is not ready — callers check {@link #isReady()}
     *                               and fall back rather than catching this
     */
    public synchronized float[] score(String query, List<String> passages) {
        if (!isReady()) throw new IllegalStateException("The reranker model is not ready.");
        float[] scores = new float[passages.size()];
        try {
            boolean wantsTypeIds = session.getInputNames().contains("token_type_ids");
            for (int start = 0; start < passages.size(); start += BATCH) {
                int end = Math.min(start + BATCH, passages.size());
                PairList<String, String> pairs = new PairList<>();
                for (int i = start; i < end; i++) pairs.add(query, passages.get(i));

                Encoding[] encodings = tokenizer.batchEncode(pairs);
                int len = encodings[0].getIds().length;
                long[][] ids = new long[encodings.length][];
                long[][] mask = new long[encodings.length][];
                long[][] typeIds = wantsTypeIds ? new long[encodings.length][] : null;
                for (int i = 0; i < encodings.length; i++) {
                    ids[i] = encodings[i].getIds();
                    mask[i] = encodings[i].getAttentionMask();
                    if (typeIds != null) {
                        long[] t = encodings[i].getTypeIds();
                        typeIds[i] = t != null && t.length == len ? t : new long[len];
                    }
                }

                List<OnnxTensor> owned = new ArrayList<>(3);
                try {
                    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                    OnnxTensor idsTensor = OnnxTensor.createTensor(environment, ids);
                    owned.add(idsTensor);
                    inputs.put("input_ids", idsTensor);
                    OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
                    owned.add(maskTensor);
                    inputs.put("attention_mask", maskTensor);
                    if (typeIds != null) {
                        OnnxTensor typeTensor = OnnxTensor.createTensor(environment, typeIds);
                        owned.add(typeTensor);
                        inputs.put("token_type_ids", typeTensor);
                    }
                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][] logits = (float[][]) result.get(0).getValue();
                        for (int i = 0; i < logits.length; i++) {
                            scores[start + i] = relevance(logits[i]);
                        }
                    }
                } finally {
                    for (OnnxTensor t : owned) t.close();
                }
            }
            return scores;
        } catch (Exception e) {
            throw new IllegalStateException("Reranking failed: " + e.getMessage(), e);
        }
    }

    /**
     * One number out of a row of logits, whichever head the export happened to use.
     *
     * <p>bge-reranker is a single-logit regression head, but the same ONNX pipeline also exports
     * two-class rerankers where relevance is the second class. Reading a two-class row as if it
     * were single-logit silently ranks by the <em>irrelevance</em> score, which produces a working
     * system that returns the worst passages — the kind of bug that survives a demo.
     */
    private static float relevance(float[] logits) {
        if (logits.length == 1) return logits[0];
        return logits[1] - logits[0];
    }

    @PreDestroy
    void closeQuietly() {
        try {
            if (session != null) session.close();
            if (tokenizer != null) tokenizer.close();
        } catch (Exception e) {
            log.debug("Closing the reranker: {}", e.getMessage());
        }
        session = null;
        tokenizer = null;
    }
}
