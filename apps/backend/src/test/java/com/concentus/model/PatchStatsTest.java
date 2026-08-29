package com.concentus.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The numbers on the review screen, counted from the patch text the way git --stat would. */
class PatchStatsTest {

    private static final String PATCH = """
            diff --git a/README.md b/README.md
            index 5626abf..f6a5d8a 100644
            --- a/README.md
            +++ b/README.md
            @@ -1 +1,2 @@
             one
            +two
            diff --git a/NEW.txt b/NEW.txt
            new file mode 100644
            index 0000000..a3c1a5e
            --- /dev/null
            +++ b/NEW.txt
            @@ -0,0 +1,2 @@
            +brand new
            +--dashes at the start of a line
            diff --git a/old.txt b/old.txt
            deleted file mode 100644
            index 1234567..0000000
            --- a/old.txt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -gone
            ---- looked like a header, was content
            """;

    @Test
    void counts_files_and_lines_without_mistaking_path_headers_for_content() {
        PatchStats stats = PatchStats.of(PATCH);

        assertThat(stats.files()).isEqualTo(3);
        // The `+++ b/…` header lines are not additions; the `+--dashes` content line is one.
        assertThat(stats.additions()).isEqualTo(3);
        // Likewise `--- a/old.txt` is a header, and the content line starting `----` is a deletion.
        assertThat(stats.deletions()).isEqualTo(2);
    }

    @Test
    void nothing_is_zero() {
        assertThat(PatchStats.of(null)).isEqualTo(PatchStats.NONE);
        assertThat(PatchStats.of("  \n")).isEqualTo(PatchStats.NONE);
    }

    @Test
    void a_binary_file_counts_as_a_file_with_no_lines() {
        String binary = """
                diff --git a/logo.png b/logo.png
                new file mode 100644
                index 0000000..89abcde
                GIT binary patch
                literal 12
                Tc$@lm+xEz0=|
                """;
        assertThat(PatchStats.of(binary)).isEqualTo(new PatchStats(1, 0, 0));
    }
}
