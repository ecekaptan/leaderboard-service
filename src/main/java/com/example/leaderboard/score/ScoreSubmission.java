package com.example.leaderboard.score;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One score submission. We store the player's id rather than a @ManyToOne Player: we never need to load
 * the player through a submission, and a plain column keeps the mapping simple (no lazy loading).
 * The foreign key in the database still guarantees the player exists.
 */
@Entity
@Table(name = "score_submissions")
public class ScoreSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(nullable = false)
    private long score;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    // Required by JPA.
    protected ScoreSubmission() {
    }

    public ScoreSubmission(UUID playerId, long score, Instant submittedAt) {
        this.playerId = playerId;
        this.score = score;
        this.submittedAt = submittedAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public long getScore() {
        return score;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
