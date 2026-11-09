package com.example.leaderboard.leaderboard;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

/**
 * The leaderboard: one Redis sorted set where member = player id and score = {@link ScoreEncoding encoded}
 * best score. Every method is a single Redis command with cost O(log N) or O(log N + M), where N is the
 * number of players on the board and M the number of entries returned.
 */
@Component
public class RedisLeaderboard {

    public static final String KEY = "leaderboard:global";

    private final StringRedisTemplate redis;

    public RedisLeaderboard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records a score, keeping only the player's best: ZADD key GT encodedScore playerId. O(log N).
     *
     * <p>GT means "only update the member if the new score is greater" (new members are always added).
     * Redis runs it as one atomic command, so concurrent submissions for the same player can't overwrite
     * a better score with a worse one. An equal score reached later encodes lower, so it doesn't replace
     * the earlier one either. The high-level ZSetOperations API has no GT option, so we use the connection.
     */
    public void submit(UUID playerId, long score, Instant achievedAt) {
        double encoded = ScoreEncoding.encode(score, achievedAt);
        redis.execute((RedisCallback<Boolean>) connection -> connection.zSetCommands()
                .zAdd(bytes(KEY), encoded, bytes(playerId.toString()), ZAddArgs.empty().gt()));
    }

    /**
     * Adds many best scores in one ZADD GT command: O(M log N) for M entries. Used by the rebuild.
     * GT makes this safe to run while live submissions are arriving: whichever score is higher wins.
     */
    public void submitAll(List<BestScore> scores) {
        Set<Tuple> tuples = new LinkedHashSet<>();
        for (BestScore best : scores) {
            double encoded = ScoreEncoding.encode(best.score(), best.achievedAt());
            tuples.add(Tuple.of(bytes(best.playerId().toString()), encoded));
        }
        redis.execute((RedisCallback<Long>) connection -> connection.zSetCommands()
                .zAdd(bytes(KEY), tuples, ZAddArgs.empty().gt()));
    }

    /** ZCARD: the number of players on the board. O(1). */
    public long size() {
        Long size = redis.opsForZSet().zCard(KEY);
        return size == null ? 0 : size;
    }

    /** ZREVRANK + ZSCORE, each O(log N). Empty if the player has no score on the board. */
    public Optional<RankedScore> findByPlayerId(UUID playerId) {
        String member = playerId.toString();
        Long zeroBasedRank = redis.opsForZSet().reverseRank(KEY, member);
        Double encoded = redis.opsForZSet().score(KEY, member);
        if (zeroBasedRank == null || encoded == null) {
            return Optional.empty();
        }
        return Optional.of(new RankedScore(playerId, ScoreEncoding.decodeScore(encoded), zeroBasedRank + 1));
    }

    /**
     * ZREVRANGE key start end WITHSCORES: O(log N + M).
     *
     * @param start 0-based position of the first entry (0 = the top player)
     * @param end   0-based position of the last entry, inclusive
     */
    public List<RankedScore> findByPositionRange(long start, long end) {
        Set<TypedTuple<String>> entries = redis.opsForZSet().reverseRangeWithScores(KEY, start, end);
        List<RankedScore> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        long rank = start + 1;
        for (TypedTuple<String> entry : entries) {
            UUID playerId = UUID.fromString(entry.getValue());
            long score = ScoreEncoding.decodeScore(entry.getScore());
            result.add(new RankedScore(playerId, score, rank));
            rank++;
        }
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
