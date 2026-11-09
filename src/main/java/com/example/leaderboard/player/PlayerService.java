package com.example.leaderboard.player;

import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final Clock clock;

    public PlayerService(PlayerRepository playerRepository, Clock clock) {
        this.playerRepository = playerRepository;
        this.clock = clock;
    }

    /**
     * Creates a player, or throws {@link UsernameTakenException} if the username exists.
     *
     * <p>We don't check "does this username exist?" before inserting: two requests could both pass that
     * check and then both insert. Instead the database's unique constraint decides, and we translate its
     * error. saveAndFlush sends the INSERT now, so the error is raised here and not at some later commit.
     */
    public Player createPlayer(String username) {
        Player player = new Player(username, Instant.now(clock));
        try {
            return playerRepository.saveAndFlush(player);
        } catch (DataIntegrityViolationException e) {
            // The username format is already validated, so the unique constraint is the only one that can fail.
            throw new UsernameTakenException(username);
        }
    }
}
