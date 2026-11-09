package com.example.leaderboard.leaderboard;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The @Min/@Max constraints on request parameters are checked by Spring MVC's built-in method validation.
 * A violation becomes a 400 problem detail (see ApiExceptionHandler).
 */
@RestController
@RequestMapping("/api/v1/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping("/top")
    public List<LeaderboardEntry> top(
            @RequestParam(defaultValue = "100")
            @Min(value = 1, message = "must be between 1 and 100")
            @Max(value = 100, message = "must be between 1 and 100")
            int limit) {
        return leaderboardService.top(limit);
    }

    @GetMapping("/players/{playerId}")
    public LeaderboardEntry player(@PathVariable UUID playerId) {
        return leaderboardService.player(playerId);
    }

    @GetMapping("/players/{playerId}/around")
    public List<LeaderboardEntry> around(
            @PathVariable UUID playerId,
            @RequestParam(defaultValue = "5")
            @Min(value = 1, message = "must be between 1 and 25")
            @Max(value = 25, message = "must be between 1 and 25")
            int range) {
        return leaderboardService.around(playerId, range);
    }
}
