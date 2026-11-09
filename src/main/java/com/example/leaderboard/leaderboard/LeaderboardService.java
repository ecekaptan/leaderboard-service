package com.example.leaderboard.leaderboard;

import com.example.leaderboard.player.Player;
import com.example.leaderboard.player.PlayerRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read side of the leaderboard: ranks and scores come from Redis, usernames from PostgreSQL.
 */
@Service
public class LeaderboardService {

    private final RedisLeaderboard leaderboard;
    private final PlayerRepository playerRepository;

    public LeaderboardService(RedisLeaderboard leaderboard, PlayerRepository playerRepository) {
        this.leaderboard = leaderboard;
        this.playerRepository = playerRepository;
    }

    /** The best {@code limit} players. */
    public List<LeaderboardEntry> top(int limit) {
        List<RankedScore> scores = leaderboard.findByPositionRange(0, limit - 1);
        return withUsernames(scores);
    }

    /** One player's rank and best score. */
    public LeaderboardEntry player(UUID playerId) {
        RankedScore score = leaderboard.findByPlayerId(playerId)
                .orElseThrow(() -> new PlayerNotRankedException(playerId));
        return withUsernames(List.of(score)).getFirst();
    }

    /**
     * The player plus up to {@code range} players above and below them. Near the top or bottom of the board
     * there are fewer neighbours on one side; we don't pad the other side to compensate.
     */
    public List<LeaderboardEntry> around(UUID playerId, int range) {
        RankedScore player = leaderboard.findByPlayerId(playerId)
                .orElseThrow(() -> new PlayerNotRankedException(playerId));

        long position = player.rank() - 1;                 // ZREVRANGE positions are 0-based
        long start = Math.max(0, position - range);        // don't go above the top of the board
        long end = position + range;                       // past the bottom is fine: Redis returns fewer entries

        List<RankedScore> scores = leaderboard.findByPositionRange(start, end);
        return withUsernames(scores);
    }

    /**
     * Adds usernames with ONE query (SELECT ... WHERE id IN (...)) instead of one query per player.
     * Doing a findById per entry would be the classic "N+1 queries" problem: 100 round trips for the top 100.
     */
    private List<LeaderboardEntry> withUsernames(List<RankedScore> scores) {
        List<UUID> playerIds = scores.stream().map(RankedScore::playerId).toList();

        Map<UUID, String> usernames = new HashMap<>();
        for (Player player : playerRepository.findAllById(playerIds)) {
            usernames.put(player.getId(), player.getUsername());
        }

        return scores.stream()
                .map(s -> new LeaderboardEntry(s.rank(), s.playerId(), usernames.get(s.playerId()), s.score()))
                .toList();
    }
}
