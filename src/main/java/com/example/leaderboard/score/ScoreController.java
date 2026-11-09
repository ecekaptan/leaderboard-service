package com.example.leaderboard.score;

import com.example.leaderboard.leaderboard.RankedScore;
import com.example.leaderboard.leaderboard.ScoreEncoding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scores")
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmitScoreResponse submitScore(@Valid @RequestBody SubmitScoreRequest request) {
        RankedScore best = scoreService.submitScore(request.playerId(), request.score());
        return new SubmitScoreResponse(best.playerId(), best.score(), best.rank());
    }

    // Long rather than long, so a missing "score" is reported by @NotNull instead of silently becoming 0.
    public record SubmitScoreRequest(
            @NotNull(message = "playerId is required")
            UUID playerId,

            @NotNull(message = "score is required")
            @PositiveOrZero(message = "score must not be negative")
            @Max(value = ScoreEncoding.MAX_SCORE, message = "score must not exceed " + ScoreEncoding.MAX_SCORE)
            Long score) {
    }

    public record SubmitScoreResponse(UUID playerId, long bestScore, long rank) {
    }
}
