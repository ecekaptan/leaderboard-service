package com.example.leaderboard.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code that needs the current time asks for a {@link Clock} instead of calling {@code Instant.now()}
 * directly, so tests can control time (tie-breaking depends on it).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
