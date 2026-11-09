package com.example.leaderboard.leaderboard;

import java.util.UUID;

/** The player is unknown or has not submitted a score yet. */
public class PlayerNotRankedException extends RuntimeException {

    public PlayerNotRankedException(UUID playerId) {
        super("Player " + playerId + " has no score on the leaderboard");
    }
}
