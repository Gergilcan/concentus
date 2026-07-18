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
