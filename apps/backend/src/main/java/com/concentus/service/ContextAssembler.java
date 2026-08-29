package com.concentus.service;

import com.concentus.config.Settings;
import com.concentus.support.TokenEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning retrieved passages into something worth reading.
 *
 * <p>Retrieval hands back a ranked list. Pasting that list into a prompt is not the same thing as
 * giving a model context, and the difference is three decisions this class makes.
 *
 * <p><b>Adjacent passages are merged.</b> Chunks overlap by design at ingest, so consecutive hits
 * from one document repeat a couple of hundred characters of each other. Passed through unmerged,
 * that repetition reads as emphasis — the model sees the same sentence twice and treats it as
 * twice as important — and it spends the budget twice on one idea.
 *
 * <p><b>Order is by document and position, never by score.</b> A document is written to be read
 * forwards. Score order hands the model step 4, then step 1, then step 7 of a process and asks it
 * to explain the process. Relevance decided which passages are here; it has no business deciding
 * what order they are read in.
 *
 * <p><b>The budget is in tokens when somebody set one, and in characters otherwise.</b> Tokens
 * are the unit a context window is measured in and the unit people reason about, so
 * {@code knowledge.context-tokens} is what the setting screen offers. There is still no tokeniser
 * here for whatever model the agent runs on — Claude through a CLI, a local model, something
 * added next month — so the count is {@link TokenEstimator}'s: a deterministic estimate, honest
 * about being within roughly a fifth of the truth, and the same for the same text every time. The
 * character budget stays as the fallback so an installation that never touched the setting
 * behaves exactly as it did.
 *
 * <p>Each span is numbered so the answer can cite it. That numbering is the whole reason a person
 * can check an answer instead of believing it.
 */
@Component
public class ContextAssembler {

    /** Below this, a budget cannot hold one real passage; a mistyped value is clamped up to it. */
    private static final int MIN_CHARS = 1000;
    /** The same floor in tokens — a thousand characters of prose. */
    private static final int MIN_TOKENS = 250;

    private final Settings settings;
    /**
     * Characters of retrieved text per source when nothing overrides it.
     *
     * <p>12,000 is roughly three thousand tokens: enough for several passages of real substance,
     * far short of the window, and — the constraint that actually binds — short enough that a flow
     * with three knowledge sources does not spend its whole context on retrieval before the agent
     * has read its instructions.
     */
    private final int fallbackChars;
    /** Tokens per source when nothing overrides it; zero means "budget in characters". */
    private final int fallbackTokens;

    // Explicit, because a second constructor exists for tests and Spring will not choose between
    // two candidates on its own — it looks for a no-arg one and fails.
    @org.springframework.beans.factory.annotation.Autowired
    public ContextAssembler(Settings settings,
                            @Value("${knowledge.context-chars:12000}") int fallbackChars,
                            @Value("${knowledge.context-tokens:0}") int fallbackTokens) {
        this.settings = settings;
        this.fallbackChars = Math.max(MIN_CHARS, fallbackChars);
        this.fallbackTokens = fallbackTokens <= 0 ? 0 : Math.max(MIN_TOKENS, fallbackTokens);
    }

    /** A fixed character budget, for tests and for callers constructing this directly. */
    public ContextAssembler(int fixedChars) {
        this(Settings.none(), fixedChars, 0);
    }

    /** What the budget is measured in — reported so a log line can say the unit it enforced. */
    public enum Unit { TOKENS, CHARS }

    /** The ceiling for one source, in the unit that applies right now. */
    public record Budget(Unit unit, int amount) {
    }

    /**
     * Read per assembly rather than captured at startup, so changing it in Settings takes effect on
     * the next run instead of the next restart. One small query against a value that was already
     * about to do a vector scan.
     *
     * <p>Tokens win when set above zero, whichever place set them; characters are what remains
     * when nobody has. Not the larger or the smaller of the two: a person who typed a token budget
     * meant it, and a character ceiling they never looked at should not overrule it.
     */
    public Budget budget() {
        int tokens = settings.number("knowledge.context-tokens", fallbackTokens);
        if (tokens > 0) return new Budget(Unit.TOKENS, Math.max(MIN_TOKENS, tokens));
        return new Budget(Unit.CHARS,
                Math.max(MIN_CHARS, settings.number("knowledge.context-chars", fallbackChars)));
    }

    /** What one passage costs against the budget, in the budget's unit. */
    private static int cost(Budget budget, String content) {
        return budget.unit() == Unit.TOKENS ? TokenEstimator.estimate(content) : content.length();
    }

    /** One contiguous span of a document, ready to be cited. */
    public record Passage(String docName, int firstSeq, int lastSeq, String content) {
        /** How a citation footnote names it: the document, and which part of it. */
        public String citation() {
            return firstSeq == lastSeq
                    ? docName + " — passage " + (firstSeq + 1)
                    : docName + " — passages " + (firstSeq + 1) + "–" + (lastSeq + 1);
        }
    }

    /**
     * @param budget what the passages were trimmed against, so a report can say "3 of 12,000
     *               chars" or "of 3,000 tokens" without re-deriving which applied
     */
    public record Assembled(List<Passage> passages, boolean truncated, Budget budget) {
        public boolean isEmpty() {
            return passages.isEmpty();
        }
    }

    /**
     * Merges, orders and trims the hits into what actually goes in the prompt.
     *
     * <p>Trimming happens <em>before</em> merging and ordering, on relevance order, because that is
     * the only stage where relevance is still known: once passages are in reading order, dropping
     * the tail would drop the end of a document rather than the least relevant thing.
     */
    public Assembled assemble(List<KnowledgeRetriever.Hit> hits) {
        Budget budget = budget();
        if (hits.isEmpty()) return new Assembled(List.of(), false, budget);

        List<KnowledgeRetriever.Hit> kept = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (KnowledgeRetriever.Hit hit : hits) {
            int cost = cost(budget, hit.content());
            if (!kept.isEmpty() && used + cost > budget.amount()) {
                truncated = true;
                continue;
            }
            kept.add(hit);
            used += cost;
        }

        List<KnowledgeRetriever.Hit> ordered = new ArrayList<>(kept);
        ordered.sort(Comparator.comparing(KnowledgeRetriever.Hit::docName)
                .thenComparingInt(KnowledgeRetriever.Hit::seq));

        return new Assembled(merge(ordered), truncated, budget);
    }

    /**
     * Joins runs of consecutive passages from one document, removing the overlap between them.
     *
     * <p>The overlap is found rather than assumed: the ingest constant could change, an external
     * database could hold chunks written by an older version, and cutting a fixed 200 characters
     * off a passage that does not start with them removes real text. The longest suffix of the
     * previous span that is also a prefix of the next is what actually repeats.
     */
    private static List<Passage> merge(List<KnowledgeRetriever.Hit> ordered) {
        Map<String, List<Passage>> byDoc = new LinkedHashMap<>();
        for (KnowledgeRetriever.Hit hit : ordered) {
            List<Passage> spans = byDoc.computeIfAbsent(hit.docName(), d -> new ArrayList<>());
            Passage last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
            if (last != null && hit.seq() == last.lastSeq() + 1) {
                spans.set(spans.size() - 1, new Passage(hit.docName(), last.firstSeq(), hit.seq(),
                        last.content() + "\n" + withoutOverlap(last.content(), hit.content())));
            } else {
                spans.add(new Passage(hit.docName(), hit.seq(), hit.seq(), hit.content()));
            }
        }
        return byDoc.values().stream().flatMap(List::stream).toList();
    }

    /** The part of {@code next} that is not already the tail of {@code previous}. */
    static String withoutOverlap(String previous, String next) {
        int max = Math.min(previous.length(), next.length());
        for (int len = max; len >= 20; len--) {
            if (previous.regionMatches(previous.length() - len, next, 0, len)) {
                return next.substring(len).stripLeading();
            }
        }
        return next;
    }

    /**
     * The retrieved context as it appears in the prompt: numbered spans and a citation key.
     *
     * <p>The instruction to cite lives here rather than in the system prompt template, because the
     * numbers it refers to are produced here. Split across two files, they drift — an answer citing
     * {@code [3]} when three passages were injected under different numbers is worse than an answer
     * with no citations at all, because it looks checkable and is not.
     */
    public String asPromptText(String label, Assembled assembled) {
        if (assembled.isEmpty()) return "(no matching passages)";

        StringBuilder text = new StringBuilder();
        List<Passage> passages = assembled.passages();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            text.append("\n[").append(i + 1).append("] ").append(p.citation()).append('\n')
                    .append(p.content()).append('\n');
        }
        text.append("\nWhen you use anything above, cite it with its number in square brackets — ")
                .append("[1], [2] — so the reader can check it. Sources for ").append(label)
                .append(":\n");
        for (int i = 0; i < passages.size(); i++) {
            text.append("[").append(i + 1).append("] ").append(passages.get(i).citation()).append('\n');
        }
        if (assembled.truncated()) {
            text.append("(More passages matched than fit; these are the most relevant.)\n");
        }
        return text.toString();
    }

    /**
     * How much a block of injected text weighs, for the run log: characters as counted, tokens
     * as estimated, and which of the two the budget was enforced in. "3 passages" says nothing
     * about cost; this is what lets a person see that three knowledge sources ate the window.
     */
    public static String describeSize(String promptText, Budget budget) {
        int chars = promptText.length();
        int tokens = TokenEstimator.estimate(promptText);
        // Locale.ROOT, because the run log is read by whoever opens it and the JVM's default on a
        // Spanish desktop writes "4.000" — which reads as four with decimals.
        return String.format(java.util.Locale.ROOT, "%,d chars, ~%,d tokens estimated; budget %,d %s",
                chars, tokens, budget.amount(), budget.unit() == Unit.TOKENS ? "tokens" : "chars");
    }
}
