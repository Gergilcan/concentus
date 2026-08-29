package com.concentus.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The estimate is a guess, so these pin the guess's shape rather than exact numbers: prose lands
 * near four characters per token, code denser, CJK one per character, and the same text always
 * costs the same. The bands are wide enough that tuning the constants a little does not break
 * them, and narrow enough that charging code like prose (or CJK like Latin) would.
 */
class TokenEstimatorTest {

    private static final String PROSE = "The quarterly review covers three regions. Each office "
            + "reports its own figures, and the finance team reconciles them before the board "
            + "meeting on the second Tuesday of the month.";

    private static final String CODE = """
            for (int i = 0; i < items.size(); i++) {
                Map<String, List<Integer>> byKey = index.get(items.get(i).key());
                if (byKey == null || byKey.isEmpty()) { continue; }
                total += byKey.values().stream().mapToInt(List::size).sum();
            }
            """;

    private static final String CJK = "東京都の本社は来月から新しい勤務時間を導入します。";

    @Test
    void proseCostsAboutFourCharactersPerToken() {
        int tokens = TokenEstimator.estimate(PROSE);
        double charsPerToken = PROSE.length() / (double) tokens;

        assertThat(charsPerToken).isBetween(3.7, 4.1);
    }

    @Test
    void codeIsDenserThanProse() {
        // Braces, operators and dots each cost a token that prose would have folded into a word.
        double codeRate = CODE.length() / (double) TokenEstimator.estimate(CODE);
        double proseRate = PROSE.length() / (double) TokenEstimator.estimate(PROSE);

        assertThat(codeRate).isLessThan(proseRate);
        assertThat(codeRate).isBetween(3.1, 3.6);
    }

    @Test
    void cjkCostsAboutOneTokenPerCharacter() {
        int tokens = TokenEstimator.estimate(CJK);

        // 25 ideographs and kana plus one fullwidth full stop.
        assertThat(tokens).isBetween(CJK.length() - 2, CJK.length() + 1);
    }

    @Test
    void mixedTextChargesEachScriptByItsOwnRule() {
        // A CJK passage with a Latin sentence in it: the sentence must not be charged one token
        // per letter, and the ideographs must not be charged one per four.
        String mixed = CJK + " See the attached policy for details. " + CJK;
        int tokens = TokenEstimator.estimate(mixed);
        int cjkAlone = TokenEstimator.estimate(CJK);
        int latinAlone = TokenEstimator.estimate(" See the attached policy for details. ");

        assertThat(tokens).isBetween(2 * cjkAlone + latinAlone - 2, 2 * cjkAlone + latinAlone + 2);
    }

    @Test
    void emptyIsFreeAndAnythingElseCostsAtLeastOne() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
    }

    @Test
    void theSameTextAlwaysCostsTheSame() {
        // A budget enforced with a number that drifts between runs would be impossible to reason
        // about; determinism is the property the whole estimator is allowed to promise.
        assertThat(TokenEstimator.estimate(PROSE)).isEqualTo(TokenEstimator.estimate(PROSE));
        assertThat(TokenEstimator.estimate(CODE)).isEqualTo(TokenEstimator.estimate(CODE));
    }

    @Test
    void supplementaryCharactersAreCountedOncePerCodePoint() {
        // An emoji is two UTF-16 units; charging it as two symbols would inflate chat-style text.
        String emoji = "👍".repeat(20);
        String marks = "!".repeat(20);

        assertThat(TokenEstimator.estimate(emoji)).isEqualTo(TokenEstimator.estimate(marks));
    }
}
