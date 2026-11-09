package com.example.leaderboard.leaderboard;

import java.util.UUID;

/** One row of the leaderboard as returned by the API. */
public record LeaderboardEntry(long rank, UUID playerId, String username, long score) {
}
