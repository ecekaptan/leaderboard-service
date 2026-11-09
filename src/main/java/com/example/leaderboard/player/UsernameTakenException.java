package com.example.leaderboard.player;

public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(String username) {
        super("Username '" + username + "' is already taken");
    }
}
