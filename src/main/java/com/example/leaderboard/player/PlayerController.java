package com.example.leaderboard.player;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/players")
public class PlayerController {

    private final PlayerService playerService;

    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlayerResponse createPlayer(@Valid @RequestBody CreatePlayerRequest request) {
        Player player = playerService.createPlayer(request.username());
        return new PlayerResponse(player.getId(), player.getUsername());
    }

    public record CreatePlayerRequest(
            @NotNull(message = "username is required")
            @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$",
                    message = "username must be 3-20 characters long and contain only letters, digits or underscores")
            String username) {
    }

    public record PlayerResponse(UUID id, String username) {
    }
}
