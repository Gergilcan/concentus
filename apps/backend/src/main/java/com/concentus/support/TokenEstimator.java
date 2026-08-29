package com.concentus.support;

/**
 * A cheap, deterministic guess at how many tokens a piece of text will cost a model.
 *
 * <p><b>Why a guess and not a tokeniser.</b> There is no one tokeniser to be right with: the agent
 * may run on Claude through a CLI, on a local model behind Ollama, or on something added next
 * month, and each cuts text differently. A real tokeniser also weighs megabytes — the built-in
 * embedder already pays for one, and a second vocabulary in the jar to answer "roughly how big is
 * this" is not worth its size. What a budget needs is a number in the units people think in, that
 * is the same every time for the same text and errs about equally in both directions.
 *
 * <p><b>The heuristic</b>, in one pass over the characters:
 * <ul>
 *   <li>CJK ideographs, kana and hangul count one token each. Subword vocabularies carry few
 *       multi-character entries for those scripts, so a character is a token near enough.</li>
 *   <li>Everything else is charged by density. Prose in a Latin script runs close to four
 *       characters per token; code and structured text — braces, operators, dots, quotes — split
 *       into far more tokens per character, and land nearer 3.2. The rate slides between those
 *       two with the share of symbol characters: 4.0 with none, 3.2 once a quarter or more of the
 *       text is symbols. Sliding rather than switching, because a threshold would let one extra
 *       bracket change the answer for a whole passage.</li>
 * </ul>
 *
 * <p><b>Honesty.</b> Expect the estimate within about 20% of what a real tokeniser reports for
 * ordinary text, either way; further out on unusual input — long runs of digits, base64, rare
 * scripts. It is for deciding how much retrieved text fits a budget and for telling a person what
 * was spent, not for billing. Anything that needs the exact count has to ask the model.
 */
public final class TokenEstimator {

    /** Characters per token for symbol-free prose. */
    private static final double PROSE_CHARS_PER_TOKEN = 4.0;
    /** Characters per token once the text is as dense with symbols as code gets. */
    private static final double CODE_CHARS_PER_TOKEN = 3.2;
    /** The symbol share at which text is charged as code. Beyond it, no denser. */
    private static final double CODE_SYMBOL_SHARE = 0.25;

    private TokenEstimator() {
    }

    /** Estimated tokens in {@code text}; zero for null or empty. Never less than one otherwise. */
    public static int estimate(CharSequence text) {
        if (text == null || text.isEmpty()) return 0;

        int cjk = 0;
        int symbols = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                cjk++;
            } else if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) {
                other++;
            } else {
                symbols++;
            }
        }

        int latinChars = other + symbols;
        double tokens = cjk;
        if (latinChars > 0) {
            double symbolShare = Math.min(1.0, (symbols / (double) latinChars) / CODE_SYMBOL_SHARE);
            double charsPerToken = PROSE_CHARS_PER_TOKEN
                    - symbolShare * (PROSE_CHARS_PER_TOKEN - CODE_CHARS_PER_TOKEN);
            tokens += latinChars / charsPerToken;
        }
        return Math.max(1, (int) Math.round(tokens));
    }

    /**
     * The scripts charged one token per character. Unicode blocks rather than script properties,
     * which is coarser but needs no tables: the unified ideographs (including extension A), the
     * two kana blocks, and precomposed hangul syllables. Fullwidth punctuation is left to the
     * symbol rule, where it costs about the same anyway.
     */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK Unified Ideographs
                || (cp >= 0x3400 && cp <= 0x4DBF) // Extension A
                || (cp >= 0x3040 && cp <= 0x30FF) // Hiragana, Katakana
                || (cp >= 0xAC00 && cp <= 0xD7AF) // Hangul syllables
                || (cp >= 0x20000 && cp <= 0x2FA1F); // Extensions B onward (supplementary plane)
    }
}
