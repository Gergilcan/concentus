package com.concentus.web;

import com.concentus.auth.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** Maps common exceptions to clean JSON error responses for the UI. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "No endpoint " + e.getResourcePath()
                        + " — is the backend running the current build?"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", safe(e)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", safe(e)));
    }

    /**
     * A record was addressed that belongs to another organization, or the caller isn't an admin.
     * Deliberately indistinguishable from "no such record": a 404-shaped message here would still
     * confirm the id exists somewhere, which is exactly what tenant isolation must not leak.
     */
    @ExceptionHandler(OrgContext.AccessDeniedForOrganization.class)
    public ResponseEntity<Map<String, String>> forbidden(OrgContext.AccessDeniedForOrganization e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", safe(e)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> generic(Exception e) {
        // Unlike badRequest/conflict above (whose messages come from our own validate() calls and
        // are safe to show), an unhandled Exception here may carry internals (stack details, driver
        // errors, etc.) that shouldn't reach the client — so this path never forwards e.getMessage().
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
