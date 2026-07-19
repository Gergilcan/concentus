package com.concentus.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiExceptionHandler}: verifies each exception type maps to the expected
 * HTTP status, that the JSON body always carries a non-null "error" message (falling back to the
 * exception's simple class name when {@code getMessage()} is null) for the 400/409 paths, and that
 * the generic/500 fallback never forwards the raw exception message to the client.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void noResourceFoundMapsTo404WithThePathInTheMessage() {
        NoResourceFoundException e = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/does-not-exist");

        ResponseEntity<Map<String, String>> response = handler.notFound(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).contains("/api/does-not-exist");
    }

    @Test
    void illegalArgumentMapsTo400WithItsMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.badRequest(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "bad input");
    }

    @Test
    void illegalStateMapsTo409WithItsMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.conflict(new IllegalStateException("already running"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "already running");
    }

    @Test
    void genericExceptionMapsTo500WithoutLeakingItsMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.generic(new RuntimeException("connection to db-prod-01 refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "Internal server error");
        assertThat(response.getBody().get("error")).doesNotContain("db-prod-01");
    }

    @Test
    void exceptionWithNoMessageFallsBackToTheSimpleClassName() {
        ResponseEntity<Map<String, String>> response = handler.badRequest(new IllegalArgumentException());

        assertThat(response.getBody()).containsEntry("error", "IllegalArgumentException");
    }
}
