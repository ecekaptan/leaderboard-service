package com.example.leaderboard.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "players")
public class Player {

    // Hibernate generates the UUID on insert. Leaving the id null until then lets Spring Data
    // see this as a new entity and run a plain INSERT (an id set by hand would trigger a SELECT first).
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Required by JPA.
    protected Player() {
    }

    public Player(String username, Instant createdAt) {
        this.username = username;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
