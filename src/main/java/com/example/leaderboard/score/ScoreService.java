package com.example.leaderboard.score;

import com.example.leaderboard.leaderboard.RankedScore;
import com.example.leaderboard.leaderboard.RedisLeaderboard;
import com.example.leaderboard.player.PlayerNotFoundException;
import com.example.leaderboard.player.PlayerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ScoreService {

    private final PlayerRepository playerRepository;
    private final ScoreSubmissionRepository scoreSubmissionRepository;
    private final RedisLeaderboard leaderboard;
    private final Clock clock;

    public ScoreService(PlayerRepository playerRepository,
                        ScoreSubmissionRepository scoreSubmissionRepository,
                        RedisLeaderboard leaderboard,
                        Clock clock) {
        this.playerRepository = playerRepository;
        this.scoreSubmissionRepository = scoreSubmissionRepository;
        this.leaderboard = leaderboard;
        this.clock = clock;
    }

    /**
     * Stores the submission and updates the leaderboard. Returns the player's best score and rank,
     * which are unchanged if this score didn't beat their best.
     *
     * <p>Order matters: PostgreSQL is written first because it is the source of truth. If the Redis write
     * then fails, the submission is safe and the client gets an error and can retry; retrying is harmless
     * because the leaderboard only keeps the maximum. The other order could put a score on the board that
     * was never stored.
     */
    public RankedScore submitScore(UUID playerId, long score) {
        if (!playerRepository.existsById(playerId)) {
            throw new PlayerNotFoundException(playerId);
        }
        Instant submittedAt = Instant.now(clock);

        scoreSubmissionRepository.save(new ScoreSubmission(playerId, score, submittedAt));
        leaderboard.submit(playerId, score, submittedAt);

        return leaderboard.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("Player " + playerId + " missing from leaderboard"));
    }
}
