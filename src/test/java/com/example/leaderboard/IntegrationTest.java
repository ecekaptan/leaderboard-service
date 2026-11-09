package com.example.leaderboard;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for integration tests: the full application, real PostgreSQL and Redis (Testcontainers),
 * and MockMvc to send HTTP requests through the whole Spring MVC stack.
 *
 * <p>All integration tests extend this class so they share one cached Spring context (and one pair of
 * containers). Because the context is shared, every test starts by deleting all data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IntegrationTest.ClockTestConfiguration.class})
public abstract class IntegrationTest {

    protected static final Instant START_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE score_submissions, players");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        clock.setInstant(START_TIME);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfiguration {

        // @Primary: wins over the real Clock bean from ClockConfig wherever a Clock is injected.
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START_TIME);
        }
    }
}
