package com.example.leaderboard.player;

import java.util.UUID;

public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(UUID playerId) {
        super("Player " + playerId + " not found");
    }
}
