package com.example.leaderboard.leaderboard;

import java.util.UUID;

/** A player's best score and 1-based rank, as stored in Redis. */
public record RankedScore(UUID playerId, long score, long rank) {
}
