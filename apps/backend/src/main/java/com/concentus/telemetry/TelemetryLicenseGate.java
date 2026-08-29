package com.concentus.telemetry;

import com.concentus.license.Feature;
import com.concentus.license.LicenseCheck;
import com.concentus.license.LicenseService;
import com.concentus.license.LicenseVerifier;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

/**
 * Keeps traces and metrics on the machine when the license does not cover sending them.
 *
 * <p>{@link Feature#OTEL_EXPORT} is the export, not the tracing: every span is still created, a
 * run behaves identically, and the {@code Telemetry} vocabulary is unchanged. What a Team license
 * does not include is the collector on the other end — so on Team the two export switches read
 * false whatever the environment or the settings screen says, and one line at startup says why.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a bean, because the exporter is one: Spring
 * Boot's OTLP auto-configuration builds it from {@code management.otlp.*.export.enabled} while the
 * context is assembling, and a check made afterwards would be arguing with an exporter that
 * already exists. Overriding the properties before any bean reads them is the one place the
 * decision holds for both the tracing and the metrics exporter without touching either. It runs
 * after the config data has loaded — the property it overrides has to have been read first — and
 * adds its own source in front of everything, which is what "whatever the setting says" means.
 *
 * <p>The license is read the way {@link LicenseCheck} reads it for the database: a throwaway
 * {@link LicenseService} off the same two sources, built before the real bean exists. Logging goes
 * through a {@link DeferredLogFactory} because at this point the logging system itself is not up
 * yet; the line is replayed once it is.
 */
public class TelemetryLicenseGate implements EnvironmentPostProcessor, Ordered {

    static final String TRACES = "management.otlp.tracing.export.enabled";
    static final String METRICS = "management.otlp.metrics.export.enabled";
    /** The name of the property source this adds, so a test (or a curious operator) can find it. */
    static final String SOURCE = "concentus-license-gate";

    /** Where the license comes from — production reads the real sources, a test its fixtures. */
    interface LicenseSource {
        LicenseService at(Path dataDir, String teamPublicKeySpki);
    }

    private final Log log;
    private final LicenseSource licenses;

    /** The constructor Spring Boot calls, with the deferred log it hands every post-processor. */
    public TelemetryLicenseGate(DeferredLogFactory logs) {
        this(logs, LicenseCheck::serviceBeforeContext);
    }

    TelemetryLicenseGate(DeferredLogFactory logs, LicenseSource licenses) {
        this.log = logs.getLog(TelemetryLicenseGate.class);
        this.licenses = licenses;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean traces = environment.getProperty(TRACES, Boolean.class, false);
        boolean metrics = environment.getProperty(METRICS, Boolean.class, false);
        // Nothing was going to leave anyway: no license to read, nothing to say.
        if (!traces && !metrics) return;

        Path dataDir = Path.of(environment.getProperty("app.data-dir", "./data"));
        String teamKey = environment.getProperty(LicenseVerifier.PROPERTY_TEAM_PUBLIC_KEY, "");
        LicenseService license = licenses.at(dataDir, teamKey);
        if (!license.withheld(Feature.OTEL_EXPORT)) return;

        environment.getPropertySources().addFirst(
                new MapPropertySource(SOURCE, Map.of(TRACES, "false", METRICS, "false")));
        log.info("Traces and metrics stay on this machine: " + license.refusal(Feature.OTEL_EXPORT)
                + " Spans are still created locally; nothing is sent to the collector.");
    }

    @Override
    public int getOrder() {
        // Right after application.properties (and the profile files) have been read: earlier, the
        // switch this overrides does not exist yet; later is fine too, but there is no later worth
        // waiting for.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
