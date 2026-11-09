package com.example.leaderboard.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PlayerServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private PlayerRepository playerRepository;

    private PlayerService playerService;

    @BeforeEach
    void setUp() {
        playerService = new PlayerService(playerRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPlayerSavesUsernameAndCreationTime() {
        when(playerRepository.saveAndFlush(any(Player.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Player player = playerService.createPlayer("alice");

        assertThat(player.getUsername()).isEqualTo("alice");
        assertThat(player.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void createPlayerThrowsUsernameTakenWhenUniqueConstraintFails() {
        when(playerRepository.saveAndFlush(any(Player.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> playerService.createPlayer("alice"))
                .isInstanceOf(UsernameTakenException.class)
                .hasMessageContaining("alice");
    }
}
