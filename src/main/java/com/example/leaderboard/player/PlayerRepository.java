package com.example.leaderboard.player;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, UUID> {
}
