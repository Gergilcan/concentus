package com.concentus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

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
        // Disable the run/version DB persistence so this test never opens a connection to the
        // real (production) Postgres/Neon instance configured as the application.properties
        // default — RunStore/FlowVersionStore both no-op their DB access when this is false.
        registry.add("app.persistence.enabled", () -> "false");
        // Also stop Hikari from proactively filling its idle pool (which would otherwise still
        // dial out to the configured datasource host in the background even with persistence
        // logically disabled above).
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        // Finally, disable the DataSource health indicator. /actuator/health probes it on every
        // request, which opens a real connection to the production Neon default in
        // application.properties — and would tie this test's UP assertion to that host being
        // reachable. Health being mapped and UP is what we assert here, not DB connectivity.
        registry.add("management.health.db.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void healthEndpointReturnsUp() {
        TestRestTemplate rest = new TestRestTemplate();
        String body = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        TestRestTemplate rest = new TestRestTemplate();
        var response = rest.getForEntity("http://localhost:" + port + "/actuator/env", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
