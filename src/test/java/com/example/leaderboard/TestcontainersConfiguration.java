package com.example.leaderboard;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts real PostgreSQL and Redis containers for integration tests.
 *
 * <p>{@code @ServiceConnection} tells Spring Boot to point the datasource and the Redis client at these
 * containers, so no connection properties are needed. Spring caches the test application context, so the
 * containers start once and are shared by every integration test that imports this class.
 *
 * <p>The image versions match docker-compose.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);
    }
}
