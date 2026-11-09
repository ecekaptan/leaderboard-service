package com.example.leaderboard.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leaderboard.leaderboard.ScoreEncoding;
import com.example.leaderboard.score.ScoreController.SubmitScoreRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubmitScoreRequestValidationTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 123_456, ScoreEncoding.MAX_SCORE})
    void acceptsScoresInRange(long score) {
        assertThat(validator.validate(new SubmitScoreRequest(PLAYER_ID, score))).isEmpty();
    }

    @Test
    void rejectsNegativeScore() {
        assertThat(messages(new SubmitScoreRequest(PLAYER_ID, -1L))).containsExactly("score must not be negative");
    }

    @Test
    void rejectsScoreAboveMaximum() {
        assertThat(messages(new SubmitScoreRequest(PLAYER_ID, ScoreEncoding.MAX_SCORE + 1)))
                .containsExactly("score must not exceed " + ScoreEncoding.MAX_SCORE);
    }

    @Test
    void rejectsMissingFields() {
        assertThat(messages(new SubmitScoreRequest(null, null)))
                .containsExactlyInAnyOrder("playerId is required", "score is required");
    }

    private static Set<String> messages(SubmitScoreRequest request) {
        Set<ConstraintViolation<SubmitScoreRequest>> violations = validator.validate(request);
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
