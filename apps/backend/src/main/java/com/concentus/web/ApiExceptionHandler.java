package com.concentus.web;

import com.concentus.auth.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** Maps common exceptions to clean JSON error responses for the UI. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * A method the endpoint doesn't support — routine, not an incident. The MCP Streamable HTTP
     * transport GETs the run-tools endpoints (with {@code Accept: text/event-stream}) probing for
     * a server-initiated stream; answering 405 is the spec's own "no stream here", and the CLI
     * carries on over POST. Before this handler, every scheduled run logged that probe as an
     * unhandled ERROR with two stack traces — and the generic handler then failed to serialize
     * its JSON body for a client that only accepts event-stream, adding a second alarm.
     * The empty body is what makes the response acceptable to any Accept header.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> methodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.debug("Method not supported: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * A status a controller chose on purpose — "No such run", "Invalid email or password".
     *
     * <p>Without this, every one of them fell through to the generic handler below and came back as
     * a 500 with the body "Internal server error", while the log carried a full stack trace for an
     * answer the code had decided to give. Two costs, both real: the browser could not tell a
     * missing run from a broken server, and a screen that polls a run id which no longer exists
     * filled the log with alarms.
     *
     * <p>The reason travels to the client because it was written to be read by whoever asked;
     * unlike the generic path, nothing here comes from a driver or a stack.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> deliberate(ResponseStatusException e) {
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        // Deliberate, so it is not an incident: at debug, where it can be turned on while chasing
        // something without being on for everyone the rest of the time.
        log.debug("Answering {} — {}", e.getStatusCode(), reason);
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> generic(Exception e) {
        // Unlike badRequest/conflict above (whose messages come from our own validate() calls and
        // are safe to show), an unhandled Exception here may carry internals (stack details, driver
        // errors, etc.) that shouldn't reach the client — so this path never forwards e.getMessage().
        //
        // It IS logged, with the stack. Hiding it from the client without recording it anywhere
        // made a 500 undiagnosable from both sides — the user saw "Internal server error" and the
        // log saw nothing at all.
        log.error("Unhandled exception on an API request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
