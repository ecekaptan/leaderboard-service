package com.example.leaderboard.common;

import com.example.leaderboard.player.PlayerNotFoundException;
import com.example.leaderboard.player.UsernameTakenException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 7807 "problem details" responses (Content-Type: application/problem+json).
 *
 * <p>The parent class, ResponseEntityExceptionHandler, already does this for Spring MVC's own errors:
 * malformed JSON, a path variable that isn't a UUID, an unsupported method and so on all become 400/405/...
 * problem details. Here we add our domain exceptions, and spell out which fields failed validation
 * (by default the detail just says "Invalid request content.").
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UsernameTakenException.class)
    public ProblemDetail handleUsernameTaken(UsernameTakenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Username taken");
        return problem;
    }

    @ExceptionHandler(PlayerNotFoundException.class)
    public ProblemDetail handlePlayerNotFound(PlayerNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Player not found");
        return problem;
    }

    /** Invalid @RequestBody, e.g. a username that is too short. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        ex.getBody().setDetail(String.join("; ", errors));
        return handleExceptionInternal(ex, ex.getBody(), headers, status, request);
    }

    /** Invalid @RequestParam or @PathVariable, e.g. limit=500. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
                .sorted()
                .toList();
        ex.getBody().setDetail(String.join("; ", errors));
        return handleExceptionInternal(ex, ex.getBody(), headers, status, request);
    }
}
