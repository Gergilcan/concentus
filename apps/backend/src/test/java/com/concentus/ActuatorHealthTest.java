package com.concentus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies /actuator/health is mapped and reports UP, and that only health + info are exposed
 * (management.endpoints.web.exposure.include=health,info in application.properties).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        // Isolate FlowStore's persistence from the repo's real data dir during tests.
        registry.add("app.data-dir", () -> dataDir.toString());
        // Point the datasource at a host that cannot resolve, so this test never opens a
        // connection to the real Postgres configured as the application.properties default.
        // Persistence is no longer switchable — every store now creates its schema on startup —
        // so isolation has to come from the connection itself rather than from a flag. The stores
        // are built to survive an unreachable database (each logs and reports itself unavailable),
        // which is exactly the state this produces.
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:1/nonexistent");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "250");
        // Stop Hikari proactively filling its idle pool in the background.
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        // Finally, disable the DataSource health indicator. /actuator/health probes it on every
        // request, which opens a real connection to the production Neon default in
        // application.properties — and would tie this test's UP assertion to that host being
        // reachable. Health being mapped and UP is what we assert here, not DB connectivity.
        registry.add("management.health.db.enabled", () -> "false");
        // Required for the app to start at all — every stored credential depends on it, so a
        // deployment without one is refused rather than left half-working. Any valid 32-byte
        // base64 key does here; nothing in this test encrypts anything.
        registry.add("app.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @LocalServerPort
    int port;

    @Test
    void healthEndpointReturnsUp() {
        TestRestTemplate rest = new TestRestTemplate();
        String body = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }

    /**
     * Two independent controls keep {@code /actuator/env} unreachable, and either one answering is
     * a pass: actuator's exposure list does not include it (404), and the security filter chain
     * denies everything under {@code /actuator} except health and info (403). The assertion is on
     * the outcome that matters — no configuration, and therefore no credential, comes back.
     */
    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        TestRestTemplate rest = new TestRestTemplate();
        var response = rest.getForEntity("http://localhost:" + port + "/actuator/env", String.class);

        assertThat(response.getStatusCode().value()).isIn(403, 404);
        assertThat(response.getBody()).doesNotContain("propertySources");
    }
}
