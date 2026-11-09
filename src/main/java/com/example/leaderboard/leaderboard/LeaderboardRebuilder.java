package com.example.leaderboard.leaderboard;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * On startup, refills the Redis leaderboard from PostgreSQL if Redis is empty (for example after Redis
 * restarted without persistence). PostgreSQL has every submission, so it can always recreate the board.
 *
 * <p>Runs as an ApplicationRunner: Spring Boot only reports the application as ready (and
 * /actuator/health/readiness as UP) after runners finish, so traffic isn't routed here mid-rebuild.
 */
@Component
public class LeaderboardRebuilder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardRebuilder.class);

    /**
     * Each player's best submission: highest score, and the earliest time they reached it. DISTINCT ON keeps
     * the first row per player_id in ORDER BY order, which the (player_id, score DESC, submitted_at) index
     * already provides. Batches use keyset pagination ("player_id > last one seen") instead of OFFSET, so each
     * batch costs the same however far into the table we are.
     */
    private static final String BEST_SCORES_SQL = """
            SELECT DISTINCT ON (player_id) player_id, score, submitted_at
            FROM score_submissions
            WHERE player_id > ?
            ORDER BY player_id, score DESC, submitted_at ASC
            LIMIT ?
            """;

    /** Sorts before every real id. Hibernate generates random (version 4) UUIDs, which are never all zeros. */
    private static final UUID LOWEST_UUID = new UUID(0, 0);

    private final JdbcTemplate jdbcTemplate;
    private final RedisLeaderboard leaderboard;
    private final int batchSize;

    public LeaderboardRebuilder(JdbcTemplate jdbcTemplate,
                                RedisLeaderboard leaderboard,
                                @Value("${leaderboard.rebuild.batch-size}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaderboard = leaderboard;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        rebuildIfEmpty();
    }

    /** Returns the number of players written to Redis (0 if no rebuild was needed). */
    public long rebuildIfEmpty() {
        long existing = leaderboard.size();
        if (existing > 0) {
            log.info("Leaderboard already has {} players in Redis; skipping rebuild", existing);
            return 0;
        }

        long startNanos = System.nanoTime();
        long playersRebuilt = 0;
        int batches = 0;
        UUID lastPlayerId = LOWEST_UUID;
        List<BestScore> batch;
        do {
            batch = jdbcTemplate.query(BEST_SCORES_SQL, LeaderboardRebuilder::toBestScore, lastPlayerId, batchSize);
            if (!batch.isEmpty()) {
                leaderboard.submitAll(batch);
                playersRebuilt += batch.size();
                batches++;
                lastPlayerId = batch.getLast().playerId();
            }
        } while (batch.size() == batchSize); // a short batch means we reached the end

        long tookMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        if (playersRebuilt == 0) {
            log.info("Leaderboard is empty and PostgreSQL has no scores; nothing to rebuild ({} ms)", tookMillis);
        } else {
            log.info("Rebuilt leaderboard from PostgreSQL: {} players in {} batches, took {} ms",
                    playersRebuilt, batches, tookMillis);
        }
        return playersRebuilt;
    }

    private static BestScore toBestScore(ResultSet row, int rowNumber) throws SQLException {
        return new BestScore(
                row.getObject("player_id", UUID.class),
                row.getLong("score"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant());
    }
}
