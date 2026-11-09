package com.example.leaderboard.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.leaderboard.leaderboard.RankedScore;
import com.example.leaderboard.leaderboard.RedisLeaderboard;
import com.example.leaderboard.player.PlayerNotFoundException;
import com.example.leaderboard.player.PlayerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private ScoreSubmissionRepository scoreSubmissionRepository;
    @Mock
    private RedisLeaderboard leaderboard;

    private ScoreService scoreService;

    @BeforeEach
    void setUp() {
        scoreService = new ScoreService(playerRepository, scoreSubmissionRepository, leaderboard,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesSubmissionInPostgresBeforeUpdatingRedis() {
        when(playerRepository.existsById(PLAYER_ID)).thenReturn(true);
        when(leaderboard.findByPlayerId(PLAYER_ID)).thenReturn(Optional.of(new RankedScore(PLAYER_ID, 300, 1)));

        scoreService.submitScore(PLAYER_ID, 300);

        ArgumentCaptor<ScoreSubmission> saved = ArgumentCaptor.forClass(ScoreSubmission.class);
        InOrder order = inOrder(scoreSubmissionRepository, leaderboard);
        order.verify(scoreSubmissionRepository).save(saved.capture());
        order.verify(leaderboard).submit(PLAYER_ID, 300, NOW);

        assertThat(saved.getValue().getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(saved.getValue().getScore()).isEqualTo(300);
        assertThat(saved.getValue().getSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    void returnsTheBestScoreFromTheLeaderboardNotTheSubmittedScore() {
        // The player already has 500; submitting 200 must report 500 and their existing rank.
        when(playerRepository.existsById(PLAYER_ID)).thenReturn(true);
        when(leaderboard.findByPlayerId(PLAYER_ID)).thenReturn(Optional.of(new RankedScore(PLAYER_ID, 500, 3)));

        RankedScore result = scoreService.submitScore(PLAYER_ID, 200);

        assertThat(result.score()).isEqualTo(500);
        assertThat(result.rank()).isEqualTo(3);
    }

    @Test
    void unknownPlayerIsRejectedWithoutWritingAnything() {
        when(playerRepository.existsById(PLAYER_ID)).thenReturn(false);

        assertThatThrownBy(() -> scoreService.submitScore(PLAYER_ID, 100))
                .isInstanceOf(PlayerNotFoundException.class);

        verifyNoInteractions(scoreSubmissionRepository, leaderboard);
    }

    @Test
    void redisFailureHappensAfterTheSubmissionIsStored() {
        when(playerRepository.existsById(PLAYER_ID)).thenReturn(true);
        doThrow(new RuntimeException("Redis down")).when(leaderboard).submit(any(), anyLong(), any());

        assertThatThrownBy(() -> scoreService.submitScore(PLAYER_ID, 100)).hasMessage("Redis down");

        // The submission was already saved: Postgres stays the complete record.
        verify(scoreSubmissionRepository).save(any(ScoreSubmission.class));
    }
}
