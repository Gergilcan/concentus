package com.concentus.llm;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An embedding model that runs inside this process — no Ollama, no Docker, no server at all.
 *
 * <p>Semantic search previously required standing up a model server, which for most desktop users
 * meant the feature simply stayed off. This runs the model in-process: ONNX Runtime executes it,
 * HuggingFace's tokenizer (via DJL) prepares the input, and both ship their native code inside
 * their jars. The only thing not bundled is the model itself — ~130 MB is too much to put in every
 * installer for a feature not everyone uses — so it is downloaded once, on demand, from inside the
 * app, into the app's data folder.
 *
 * <p>The model is multilingual-e5-small (quantized): multilingual — which matters for a user base
 * that writes documents in Spanish — 384 dimensions, and small enough to download on a coffee
 * break and run on any CPU. Not bge-m3, deliberately: that model is 2.2 GB, and anyone wanting it
 * can serve it from Ollama, which remains fully supported and takes precedence never — the
 * built-in model wins when ready, because it is the one the user explicitly downloaded.
 *
 * <p>E5 models are trained with role prefixes; embedding without them measurably degrades
 * retrieval, so {@link #embed} takes which side it is embedding and applies "query: " or
 * "passage: " itself rather than trusting every caller to remember.
 */
@Component
public class BuiltInEmbedder {

    private static final Logger log = LoggerFactory.getLogger(BuiltInEmbedder.class);

    /**
     * The model, fixed rather than configurable. It was two @Value knobs nothing set and nothing
     * could safely use: the "query: "/"passage: " prefixes below and the name shown in the UI are
     * E5-specific, so pointing these elsewhere produced wrong prefixes and a UI naming the wrong
     * model — an option that lies is worse than no option.
     */
    public static final String MODEL_NAME = "multilingual-e5-small";
    private static final String MODEL_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx";
    private static final String TOKENIZER_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json";

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

    // Explicit: a second, package-private constructor exists for tests, and Spring will not choose
    // between two candidates on its own — it looks for a no-arg one and fails.
    @org.springframework.beans.factory.annotation.Autowired
    public BuiltInEmbedder(@Value("${app.data-dir}") String dataDir) {
        this(dataDir, MODEL_URL, TOKENIZER_URL);
    }

    /**
     * Visible for tests, which point the download at an unreachable URL to exercise the failure
     * path. Deliberately not a configuration property: the "query: "/"passage: " prefixes and the
     * name shown in the UI are E5-specific, so a knob aiming this elsewhere would lie.
     */
    BuiltInEmbedder(String dataDir, String modelUrl, String tokenizerUrl) {
        this.dir = Path.of(dataDir).toAbsolutePath().resolve("models").resolve("embedding");
        this.modelUrl = modelUrl;
        this.tokenizerUrl = tokenizerUrl;
    }

    /**
     * Loads an already-downloaded model, off the startup path — and off the startup thread.
     *
     * <p>Opening the ONNX session reads ~130 MB and builds a tokenizer, about half a second. The
     * constructor was the first wrong place for that; the event thread was the second, because
     * ApplicationReadyEvent listeners run before the readiness probe flips, so a downloaded model
     * taxed every launch by that much. Publication is safe because load() sets the READY state
     * last, through the AtomicReference. A failure degrades to NOT_DOWNLOADED rather than
     * stopping the app — the download button then offers a redo.
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void loadIfPresentAsync() {
        if (!Files.isRegularFile(modelFile()) || !Files.isRegularFile(tokenizerFile())) return;
        Thread loader = new Thread(this::loadIfPresent, "embedding-model-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** Loads an already-downloaded model synchronously; also the seam the embedding tests use. */
    void loadIfPresent() {
        if (!Files.isRegularFile(modelFile()) || !Files.isRegularFile(tokenizerFile())) return;
        try {
            load();
        } catch (Exception e) {
            log.warn("Built-in embedding model present but unloadable ({}); offering re-download.",
                    e.getMessage());
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

    /** 0–100 while downloading; meaningless otherwise. */
    public int progressPercent() {
        long total = totalBytes.get();
        return total <= 0 ? 0 : (int) (downloadedBytes.get() * 100 / total);
    }

    /**
     * Starts the download on a background thread; returns immediately. Progress is polled through
     * {@link #progressPercent()} — a 130 MB download behind a frozen button is indistinguishable
     * from a hang, which is the mistake the installer flow already taught us not to repeat.
     */
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
                log.info("Built-in embedding model ready at {}", dir);
            } catch (Exception e) {
                log.error("Built-in embedding model download failed", e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                state.set(State.ERROR);
            }
        }, "embedding-model-download");
        worker.setDaemon(true);
        worker.start();
    }

    /** Streams to a temp file and renames, so a killed download never leaves a half-model behind. */
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
                // The model's positional ceiling; longer inputs are truncated rather than refused,
                // because chunking upstream already keeps passages far below this.
                .optMaxLength(512)
                .optTruncation(true)
                // Pad to the longest text in each batch, not always to 512: a one-word query used
                // to cost a full 512-token forward pass, and every chunk paid for the padding of
                // the ceiling rather than of its batch.
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
     * Embeds a batch. Synchronized because the tokenizer is not documented thread-safe, and
     * ingest-vs-search races are real: an upload and a query can overlap.
     *
     * @param queries true when embedding search queries, false for stored passages — E5's
     *                training makes the distinction, so this API does too
     */
    /** Batch size per forward pass: big enough to amortise, small enough to keep memory flat. */
    private static final int BATCH = 16;

    public synchronized List<float[]> embed(List<String> texts, boolean queries) {
        if (!isReady()) throw new IllegalStateException("The built-in embedding model is not ready.");
        String prefix = queries ? "query: " : "passage: ";
        List<float[]> out = new ArrayList<>(texts.size());
        try {
            boolean wantsTypeIds = session.getInputNames().contains("token_type_ids");
            for (int start = 0; start < texts.size(); start += BATCH) {
                List<String> slice = texts.subList(start, Math.min(start + BATCH, texts.size()));
                String[] prefixed = new String[slice.size()];
                for (int i = 0; i < slice.size(); i++) prefixed[i] = prefix + slice.get(i);

                // One forward pass per batch instead of per text: ingesting a document capped at
                // 2000 chunks used to mean 2000 separate inferences.
                Encoding[] encodings = tokenizer.batchEncode(prefixed);
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
                    Map<String, OnnxTensor> inputs = new java.util.LinkedHashMap<>();
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
                        float[][][] hidden = (float[][][]) result.get(0).getValue();
                        for (int i = 0; i < hidden.length; i++) {
                            out.add(meanPool(hidden[i], mask[i]));
                        }
                    }
                } finally {
                    for (OnnxTensor t : owned) t.close();
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Built-in embedding failed: " + e.getMessage(), e);
        }
    }

    /** Mean over the real (unpadded) tokens, L2-normalised — E5's documented pooling. */
    private static float[] meanPool(float[][] hidden, long[] mask) {
        int dims = hidden[0].length;
        float[] sum = new float[dims];
        int count = 0;
        for (int t = 0; t < hidden.length && t < mask.length; t++) {
            if (mask[t] == 0) continue;
            count++;
            for (int d = 0; d < dims; d++) sum[d] += hidden[t][d];
        }
        if (count == 0) return sum;
        double norm = 0;
        for (int d = 0; d < dims; d++) {
            sum[d] /= count;
            norm += sum[d] * sum[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) for (int d = 0; d < dims; d++) sum[d] /= norm;
        return sum;
    }

    @PreDestroy
    void closeQuietly() {
        try {
            if (session != null) session.close();
            if (tokenizer != null) tokenizer.close();
        } catch (Exception e) {
            log.debug("Closing the embedder: {}", e.getMessage());
        }
        session = null;
        tokenizer = null;
    }
}
