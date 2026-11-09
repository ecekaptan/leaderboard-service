package com.example.leaderboard.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leaderboard.player.PlayerController.CreatePlayerRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the username rule (3-20 chars: letters, digits, underscore) without starting Spring. */
class CreatePlayerRequestValidationTest {

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
    @ValueSource(strings = {"abc", "Player_1", "UPPER_lower_123", "abcdefghijklmnopqrst" /* 20 chars */})
    void acceptsValidUsernames(String username) {
        assertThat(validator.validate(new CreatePlayerRequest(username))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "abcdefghijklmnopqrstu" /* 21 chars */, "has space", "dash-name", "émile", "a.b.c"})
    void rejectsInvalidUsernames(String username) {
        assertThat(validator.validate(new CreatePlayerRequest(username))).hasSize(1);
    }

    @Test
    void rejectsMissingUsername() {
        assertThat(validator.validate(new CreatePlayerRequest(null)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage()).isEqualTo("username is required"));
    }
}
