package com.concentus.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextsTest {

    @Test
    void underTheCeilingPassesStripped() {
        assertThat(Texts.brief("  hola  ", 10)).isEqualTo("hola");
        assertThat(Texts.brief(null, 10)).isEmpty();
    }

    @Test
    void overTheCeilingIsCutAndMarked() {
        assertThat(Texts.brief("a".repeat(20), 10)).isEqualTo("a".repeat(10) + "…");
    }

    @Test
    void neverSplitsASurrogatePair() {
        String text = "ab" + "😀" + "cd"; // the emoji occupies indices 2-3

        String cut = Texts.brief(text, 3);

        assertThat(cut).isEqualTo("ab…");
    }
}
