package com.example.leaderboard.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.leaderboard.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class LeaderboardApiIT extends IntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * Creates players p01..pNN where p01 has the highest score, so p01 is rank 1 and pNN is rank N.
     * Returns their ids in rank order.
     */
    private List<UUID> createRankedPlayers(int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            UUID id = createPlayer("p%02d".formatted(i));
            submitScore(id, 10_000 - i).andExpect(status().isCreated());
            ids.add(id);
        }
        return ids;
    }

    @Nested
    class Top {

        @Test
        void returnsPlayersOrderedByBestScoreWithRanksAndUsernames() throws Exception {
            UUID alice = createPlayer("alice");
            UUID bob = createPlayer("bob");
            UUID carol = createPlayer("carol");
            submitScore(alice, 300);
            submitScore(bob, 900);
            submitScore(carol, 600);

            mockMvc.perform(get("/api/v1/leaderboard/top"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[*].username", contains("bob", "carol", "alice")))
                    .andExpect(jsonPath("$[*].rank", contains(1, 2, 3)))
                    .andExpect(jsonPath("$[*].score", contains(900, 600, 300)))
                    .andExpect(jsonPath("$[0].playerId").value(bob.toString()));
        }

        @Test
        void breaksTiesByWhoReachedTheScoreFirst() throws Exception {
            UUID first = createPlayer("first");
            UUID second = createPlayer("second");
            UUID third = createPlayer("third");

            submitScore(second, 500);
            clock.advance(Duration.ofSeconds(1));
            submitScore(first, 900);
            submitScore(third, 500);
            clock.advance(Duration.ofSeconds(1));
            // "second" re-submits 500 later: it keeps its original (earlier) time, so it stays ahead of "third".
            submitScore(second, 500);

            mockMvc.perform(get("/api/v1/leaderboard/top"))
                    .andExpect(jsonPath("$[*].username", contains("first", "second", "third")))
                    .andExpect(jsonPath("$[*].score", contains(900, 500, 500)));
        }

        @Test
        void lowerScoreDoesNotChangeTheBoard() throws Exception {
            UUID alice = createPlayer("alice");
            UUID bob = createPlayer("bob");
            submitScore(alice, 500);
            submitScore(bob, 400);

            clock.advance(Duration.ofMinutes(5));
            submitScore(alice, 1);

            mockMvc.perform(get("/api/v1/leaderboard/top"))
                    .andExpect(jsonPath("$[*].username", contains("alice", "bob")))
                    .andExpect(jsonPath("$[*].score", contains(500, 400)));
        }

        @Test
        void respectsLimit() throws Exception {
            createRankedPlayers(5);

            mockMvc.perform(get("/api/v1/leaderboard/top").param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].username", contains("p01", "p02")));
        }

        @Test
        void defaultLimitIs100() throws Exception {
            createRankedPlayers(101);

            mockMvc.perform(get("/api/v1/leaderboard/top"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(100)))
                    .andExpect(jsonPath("$[99].rank").value(100));
        }

        @Test
        void emptyBoardReturnsEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/leaderboard/top"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void loadsAllUsernamesWithOneSqlStatement() throws Exception {
            createRankedPlayers(20);
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            mockMvc.perform(get("/api/v1/leaderboard/top"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(20)));

            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "101", "-5"})
        void rejectsLimitOutsideOneToHundred(String limit) throws Exception {
            mockMvc.perform(get("/api/v1/leaderboard/top").param("limit", limit))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("limit: must be between 1 and 100"));
        }

        @Test
        void rejectsNonNumericLimit() throws Exception {
            mockMvc.perform(get("/api/v1/leaderboard/top").param("limit", "ten"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Nested
    class PlayerRank {

        @Test
        void returnsRankScoreAndUsername() throws Exception {
            List<UUID> ids = createRankedPlayers(3);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}", ids.get(1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rank").value(2))
                    .andExpect(jsonPath("$.playerId").value(ids.get(1).toString()))
                    .andExpect(jsonPath("$.username").value("p02"))
                    .andExpect(jsonPath("$.score").value(9_998));
        }

        @Test
        void rankDoesNotChangeAfterALowerScore() throws Exception {
            List<UUID> ids = createRankedPlayers(3);
            clock.advance(Duration.ofMinutes(1));
            submitScore(ids.get(1), 5);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}", ids.get(1)))
                    .andExpect(jsonPath("$.rank").value(2))
                    .andExpect(jsonPath("$.score").value(9_998));
        }

        @Test
        void returns404ForUnknownPlayer() throws Exception {
            mockMvc.perform(get("/api/v1/leaderboard/players/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Player not ranked"));
        }

        @Test
        void returns404ForPlayerWithoutAScore() throws Exception {
            UUID alice = createPlayer("alice");

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}", alice))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(startsWith("Player " + alice)));
        }

        @Test
        void returns400ForMalformedPlayerId() throws Exception {
            mockMvc.perform(get("/api/v1/leaderboard/players/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Nested
    class Around {

        @Test
        void returnsPlayerWithNeighboursAboveAndBelow() throws Exception {
            List<UUID> ids = createRankedPlayers(10);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", ids.get(4)).param("range", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].rank", contains(3, 4, 5, 6, 7)))
                    .andExpect(jsonPath("$[*].username", contains("p03", "p04", "p05", "p06", "p07")));
        }

        @Test
        void atTheTopOnlyReturnsPlayersBelow() throws Exception {
            List<UUID> ids = createRankedPlayers(10);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", ids.get(0)).param("range", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].rank", contains(1, 2, 3)));
        }

        @Test
        void atTheBottomOnlyReturnsPlayersAbove() throws Exception {
            List<UUID> ids = createRankedPlayers(10);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", ids.get(9)).param("range", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].rank", contains(8, 9, 10)));
        }

        @Test
        void defaultRangeIsFive() throws Exception {
            List<UUID> ids = createRankedPlayers(20);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", ids.get(9)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].rank", contains(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)));
        }

        @Test
        void onlyPlayerOnTheBoardGetsJustThemselves() throws Exception {
            UUID alice = createPlayer("alice");
            submitScore(alice, 10);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", alice))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].username").value("alice"));
        }

        @Test
        void returns404ForUnknownPlayer() throws Exception {
            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "26"})
        void rejectsRangeOutsideOneToTwentyFive(String range) throws Exception {
            UUID alice = createPlayer("alice");
            submitScore(alice, 10);

            mockMvc.perform(get("/api/v1/leaderboard/players/{id}/around", alice).param("range", range))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("range: must be between 1 and 25"));
        }
    }
}
