package com.example.leaderboard.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScoreEncodingTest {

    private static final Instant EARLY = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-01-01T00:00:01Z");

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 42, 1_000_000, ScoreEncoding.MAX_SCORE})
    void decodeReturnsTheOriginalScore(long score) {
        assertThat(ScoreEncoding.decodeScore(ScoreEncoding.encode(score, EARLY))).isEqualTo(score);
        assertThat(ScoreEncoding.decodeScore(ScoreEncoding.encode(score, ScoreEncoding.EPOCH))).isEqualTo(score);
        assertThat(ScoreEncoding.decodeScore(ScoreEncoding.encode(score, ScoreEncoding.LATEST_TIME.minusSeconds(1))))
                .isEqualTo(score);
    }

    @Test
    void higherScoreRanksHigherEvenWhenReachedLater() {
        // Worst case for the time part: the higher score at the latest possible time,
        // the lower score at the earliest.
        double lowerScoreEarliest = ScoreEncoding.encode(100, ScoreEncoding.EPOCH);
        double higherScoreLatest = ScoreEncoding.encode(101, ScoreEncoding.LATEST_TIME.minusSeconds(1));

        assertThat(higherScoreLatest).isGreaterThan(lowerScoreEarliest);
    }

    @Test
    void onEqualScoresTheEarlierTimeRanksHigher() {
        assertThat(ScoreEncoding.encode(500, EARLY)).isGreaterThan(ScoreEncoding.encode(500, LATE));
    }

    @Test
    void timesWithinTheSameSecondEncodeEqually() {
        // Documented limit: precision is one second.
        Instant sameSecondLater = EARLY.plusMillis(999);
        assertThat(ScoreEncoding.encode(500, EARLY)).isEqualTo(ScoreEncoding.encode(500, sameSecondLater));
    }

    @Test
    void largestEncodedValueIsExactlyRepresentableAsADouble() {
        double largest = ScoreEncoding.encode(ScoreEncoding.MAX_SCORE, ScoreEncoding.EPOCH);

        assertThat(largest).isLessThan(Math.pow(2, 53));
        // If the value were not exact, converting back to long would give a different number.
        long expected = ScoreEncoding.MAX_SCORE * ScoreEncoding.TIME_SLOTS + (ScoreEncoding.TIME_SLOTS - 1);
        assertThat((long) largest).isEqualTo(expected);
    }

    @Test
    void neighbouringValuesNearTheTopStayDistinct() {
        double a = ScoreEncoding.encode(ScoreEncoding.MAX_SCORE, EARLY);
        double b = ScoreEncoding.encode(ScoreEncoding.MAX_SCORE, LATE);
        assertThat(a - b).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, ScoreEncoding.MAX_SCORE + 1, Long.MAX_VALUE})
    void rejectsScoresOutsideTheSupportedRange(long score) {
        assertThatThrownBy(() -> ScoreEncoding.encode(score, EARLY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTimesOutsideTheSupportedRange() {
        assertThatThrownBy(() -> ScoreEncoding.encode(1, ScoreEncoding.EPOCH.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreEncoding.encode(1, ScoreEncoding.LATEST_TIME))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
