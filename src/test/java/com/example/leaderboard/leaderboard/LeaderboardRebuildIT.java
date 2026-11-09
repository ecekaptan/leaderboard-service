package com.example.leaderboard.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.leaderboard.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.core.RedisCallback;

class LeaderboardRebuildIT extends IntegrationTest {

    @Autowired
    private LeaderboardRebuilder rebuilder;

    @Autowired
    private RedisLeaderboard leaderboard;

    @Test
    void rebuildsTheSameBoardAfterRedisIsFlushed() throws Exception {
        UUID alice = createPlayer("alice");
        UUID bob = createPlayer("bob");
        UUID carol = createPlayer("carol");
        UUID dave = createPlayer("dave");
        createPlayer("no_scores");

        submitScore(bob, 700);
        submitScore(carol, 400);
        clock.advance(Duration.ofSeconds(1));
        submitScore(alice, 700);          // ties bob, but later: bob must stay ahead
        submitScore(dave, 900);
        clock.advance(Duration.ofSeconds(1));
        submitScore(dave, 100);           // lower: ignored
        submitScore(bob, 700);            // same score again, later: bob keeps his earlier time
        submitScore(carol, 650);          // improvement

        String boardBefore = getTop();
        String aliceBefore = getPlayer(alice);

        flushRedis();
        assertThat(getTop()).isEqualTo("[]");

        rebuilder.run(new DefaultApplicationArguments());

        assertThat(getTop()).isEqualTo(boardBefore);
        assertThat(getPlayer(alice)).isEqualTo(aliceBefore);
        assertThat(leaderboard.findByPositionRange(0, 3))
                .extracting(RankedScore::playerId)
                .containsExactly(dave, bob, alice, carol);
    }

    @ParameterizedTest(name = "{0} players with batch size 2")
    @ValueSource(ints = {1, 2, 6, 7})
    void rebuildsEveryPlayerAcrossBatchBoundaries(int players) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            UUID id = createPlayer("player_" + i);
            submitScore(id, 1_000 - i);
            submitScore(id, 10);   // a second, lower submission per player
            ids.add(id);
        }
        flushRedis();

        LeaderboardRebuilder smallBatches = new LeaderboardRebuilder(jdbcTemplate, leaderboard, 2);
        long rebuilt = smallBatches.rebuildIfEmpty();

        assertThat(rebuilt).isEqualTo(players);
        assertThat(leaderboard.findByPositionRange(0, -1))
                .extracting(RankedScore::playerId)
                .containsExactlyElementsOf(ids);
    }

    @Test
    void skipsRebuildWhenRedisAlreadyHasData() throws Exception {
        UUID alice = createPlayer("alice");
        UUID bob = createPlayer("bob");
        submitScore(alice, 100);
        submitScore(bob, 200);
        // Simulate partial data loss: the board isn't empty, so the startup check doesn't rebuild.
        redisTemplate.opsForZSet().remove(RedisLeaderboard.KEY, alice.toString());

        assertThat(rebuilder.rebuildIfEmpty()).isZero();
        assertThat(leaderboard.size()).isEqualTo(1);
    }

    @Test
    void doesNothingWhenThereAreNoScores() throws Exception {
        createPlayer("alice");

        assertThat(rebuilder.rebuildIfEmpty()).isZero();
        assertThat(leaderboard.size()).isZero();
    }

    private String getTop() throws Exception {
        return mockMvc.perform(get("/api/v1/leaderboard/top"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String getPlayer(UUID playerId) throws Exception {
        return mockMvc.perform(get("/api/v1/leaderboard/players/{id}", playerId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
