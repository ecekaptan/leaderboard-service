package com.example.leaderboard.score;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoreSubmissionRepository extends JpaRepository<ScoreSubmission, Long> {
}
