package com.example.leaderboard.leaderboard;

import java.time.Instant;
import java.util.UUID;

/** A player's best score and the time they first reached it. */
public record BestScore(UUID playerId, long score, Instant achievedAt) {
}
