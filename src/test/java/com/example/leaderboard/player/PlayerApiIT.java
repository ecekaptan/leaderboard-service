package com.example.leaderboard.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.leaderboard.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class PlayerApiIT extends IntegrationTest {

    @Test
    void createsPlayer() throws Exception {
        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.username").value("alice"));

        Instant createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM players WHERE username = 'alice'", Instant.class);
        assertThat(createdAt).isEqualTo(START_TIME);
    }

    @Test
    void returns409WhenUsernameIsTaken() throws Exception {
        String body = """
                {"username": "alice"}
                """;
        mockMvc.perform(post("/api/v1/players").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/players").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Username taken"))
                .andExpect(jsonPath("$.detail").value("Username 'alice' is already taken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"username\": \"ab\"}",
            "{\"username\": \"abcdefghijklmnopqrstu\"}",
            "{\"username\": \"bad-name\"}",
            "{}"
    })
    void returns400ForInvalidUsername(String body) throws Exception {
        mockMvc.perform(post("/api/v1/players").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(startsWith("username: ")));
    }

    @Test
    void returns400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/players").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
