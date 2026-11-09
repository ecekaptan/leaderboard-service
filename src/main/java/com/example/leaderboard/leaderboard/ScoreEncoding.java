package com.example.leaderboard.leaderboard;

import java.time.Duration;
import java.time.Instant;

/**
 * Packs a game score and the time it was reached into one Redis sorted-set score, so that Redis orders
 * players by score (highest first) and, on equal scores, by time (earliest first).
 *
 * <pre>
 *   encoded = score * 2^32 + (2^32 - 1 - secondsSinceEpoch)
 *             \__ high part _/  \______ low part ______/
 * </pre>
 *
 * <ul>
 *   <li>The low part is always between 0 and 2^32 - 1, so it can never "carry" into the score part:
 *       a higher score always produces a higher encoded value, whatever the times are.</li>
 *   <li>For equal scores, an earlier time gives a larger low part, so the earlier player ranks higher.</li>
 *   <li>Redis stores sorted-set scores as 64-bit doubles, which hold every integer up to 2^53 exactly.
 *       32 bits go to time, which leaves 21 bits for the score: the maximum score is 2^21 - 1.</li>
 *   <li>Time has one-second precision. Two players reaching the same score in the same second get the
 *       same encoded value, and Redis then orders them by member (player id) – stable but arbitrary.</li>
 * </ul>
 */
public final class ScoreEncoding {

    /** Times are counted in seconds from this instant. */
    public static final Instant EPOCH = Instant.parse("2025-01-01T00:00:00Z");

    /** Number of distinct seconds we can encode: 2^32 seconds is about 136 years (until 2161). */
    static final long TIME_SLOTS = 1L << 32;

    /** Largest score that keeps the encoded value below 2^53: 2^21 - 1 = 2,097,151. */
    public static final long MAX_SCORE = (1L << 21) - 1;

    /** The last instant we can encode (exclusive). */
    public static final Instant LATEST_TIME = EPOCH.plusSeconds(TIME_SLOTS);

    private ScoreEncoding() {
    }

    public static double encode(long score, Instant achievedAt) {
        if (score < 0 || score > MAX_SCORE) {
            throw new IllegalArgumentException("score must be between 0 and " + MAX_SCORE + ", was " + score);
        }
        if (achievedAt.isBefore(EPOCH) || !achievedAt.isBefore(LATEST_TIME)) {
            throw new IllegalArgumentException(
                    "time must be between " + EPOCH + " and " + LATEST_TIME + ", was " + achievedAt);
        }
        long secondsSinceEpoch = Duration.between(EPOCH, achievedAt).getSeconds();
        long timePart = TIME_SLOTS - 1 - secondsSinceEpoch;
        return (double) (score * TIME_SLOTS + timePart);
    }

    public static long decodeScore(double encoded) {
        // The cast is exact: encoded values are whole numbers below 2^53.
        return (long) encoded / TIME_SLOTS;
    }
}
