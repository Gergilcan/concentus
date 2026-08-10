package com.concentus.integration.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedTextTest {

    @Test
    void underTheCeilingPassesUntouched() {
        assertThat(ExtractedText.clamp("hola", 10)).isEqualTo("hola");
        assertThat(ExtractedText.clamp(null, 10)).isNull();
    }

    @Test
    void overTheCeilingIsCutAndMarked() {
        String clamped = ExtractedText.clamp("a".repeat(20), 10);

        assertThat(clamped).startsWith("a".repeat(10)).endsWith("…(truncated)");
    }

    @Test
    void neverSplitsASupplementaryCharacter() {
        // An emoji is two chars (a surrogate pair); a cut landing between them leaves a lone
        // surrogate that some JSON writers refuse to encode — the bug the six copies shared.
        String text = "ab" + "😀" + "cd"; // ab😀cd, the emoji at indices 2-3
        String clamped = ExtractedText.clamp(text, 3);

        assertThat(clamped).startsWith("ab");
        assertThat(clamped).doesNotContain("😀");
        // No lone surrogate survives anywhere in the output.
        for (int i = 0; i < clamped.length(); i++) {
            char c = clamped.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1).isLessThan(clamped.length());
                assertThat(Character.isLowSurrogate(clamped.charAt(i + 1))).isTrue();
            }
        }
    }
}
