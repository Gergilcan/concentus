package com.concentus.integration.content;

/** The one text clamp every extractor applies, instead of six private copies of it. */
final class ExtractedText {

    private ExtractedText() {
    }

    /**
     * Clamps extracted text to a ceiling, marking the cut.
     *
     * <p>Surrogate-safe, which the six copies this replaces were not: {@code substring} at a fixed
     * index can split a supplementary character (an emoji, a rare CJK ideograph) in half, leaving
     * a lone surrogate that some downstream JSON writers refuse to encode. The cut moves back one
     * position when it would land mid-pair.
     */
    static String clamp(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        int cut = maxChars;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        return text.substring(0, cut) + "\n…(truncated)";
    }
}
