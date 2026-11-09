package com.example.leaderboard.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.leaderboard.IntegrationTest;
import com.example.leaderboard.leaderboard.ScoreEncoding;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class ScoreApiIT extends IntegrationTest {

    @Test
    void firstSubmissionPutsPlayerOnTheBoard() throws Exception {
        UUID alice = createPlayer("alice");

        submitScore(alice, 300)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").value(alice.toString()))
                .andExpect(jsonPath("$.bestScore").value(300))
                .andExpect(jsonPath("$.rank").value(1));
    }

    @Test
    void lowerScoreIsStoredButDoesNotChangeBestScoreOrRank() throws Exception {
        UUID alice = createPlayer("alice");
        UUID bob = createPlayer("bob");
        submitScore(alice, 500);
        submitScore(bob, 400);

        clock.advance(Duration.ofMinutes(1));
        submitScore(bob, 100)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bestScore").value(400))
                .andExpect(jsonPath("$.rank").value(2));

        // Every submission is kept in Postgres, including the lower one.
        Integer bobSubmissions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM score_submissions WHERE player_id = ?", Integer.class, bob);
        assertThat(bobSubmissions).isEqualTo(2);
    }

    @Test
    void higherScoreImprovesRank() throws Exception {
        UUID alice = createPlayer("alice");
        UUID bob = createPlayer("bob");
        submitScore(alice, 500);
        submitScore(bob, 400).andExpect(jsonPath("$.rank").value(2));

        clock.advance(Duration.ofMinutes(1));
        submitScore(bob, 900)
                .andExpect(jsonPath("$.bestScore").value(900))
                .andExpect(jsonPath("$.rank").value(1));
    }

    @Test
    void onATieThePlayerWhoReachedTheScoreFirstRanksHigher() throws Exception {
        UUID alice = createPlayer("alice");
        UUID bob = createPlayer("bob");

        submitScore(bob, 500);
        clock.advance(Duration.ofSeconds(1));
        submitScore(alice, 500).andExpect(jsonPath("$.rank").value(2));

        // Bob submitting the same score again later must not lose his earlier timestamp.
        clock.advance(Duration.ofSeconds(1));
        submitScore(bob, 500).andExpect(jsonPath("$.rank").value(1));
    }

    @Test
    void returns404ForUnknownPlayer() throws Exception {
        submitScore(UUID.randomUUID(), 100)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Player not found"));

        Integer submissions = jdbcTemplate.queryForObject("SELECT count(*) FROM score_submissions", Integer.class);
        assertThat(submissions).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, ScoreEncoding.MAX_SCORE + 1})
    void returns400ForScoreOutOfRange(long score) throws Exception {
        UUID alice = createPlayer("alice");

        submitScore(alice, score)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(startsWith("score: ")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"score\": 10}",
            "{\"playerId\": \"not-a-uuid\", \"score\": 10}",
            "{\"playerId\": \"00000000-0000-0000-0000-000000000001\"}"
    })
    void returns400ForMissingOrMalformedFields(String body) throws Exception {
        mockMvc.perform(post("/api/v1/scores").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
