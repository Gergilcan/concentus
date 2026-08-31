package com.concentus.telemetry;

import com.concentus.license.Feature;
import com.concentus.license.TestLicenses;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature.OTEL_EXPORT at the one place it can be enforced: before the exporter exists.
 *
 * <p>Metrics are the whole of what it withholds. Team: the metrics switch reads false afterwards
 * and one line says why, while the traces switch is left exactly as configured. Enterprise and
 * free: untouched. And with the metrics export already off, nothing happens at all, not even a
 * license read: there is nothing to withhold and nothing to say.
 */
class TelemetryLicenseGateTest {

    /** Traces are not gated on any tier; the test asserts on the property by its own name. */
    private static final String TRACES = "management.otlp.tracing.export.enabled";

    private final List<String> logged = new ArrayList<>();

    /** A log that keeps its info lines; the rest of the commons-logging surface answers nothing. */
    private final DeferredLogFactory logs = supplier -> (Log) Proxy.newProxyInstance(
            Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
                if (method.getName().equals("info")) logged.add(String.valueOf(args[0]));
                return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
            });

    private TelemetryLicenseGate gate() {
        // The fixture verifier rather than the production one, so the committed licenses verify.
        return new TelemetryLicenseGate(logs, (dataDir, teamKey) -> {
            try {
                return TestLicenses.serviceOn(dataDir);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static MockEnvironment environmentOn(Path dataDir, boolean traces, boolean metrics) {
        return new MockEnvironment()
                .withProperty("app.data-dir", dataDir.toString())
                .withProperty(TRACES, String.valueOf(traces))
                .withProperty(TelemetryLicenseGate.METRICS, String.valueOf(metrics));
    }

    @Test
    void on_a_team_license_metrics_are_forced_off_and_traces_are_left_alone(@TempDir Path dir)
            throws Exception {
        TestLicenses.installFixture(dir, "team-test.license");
        MockEnvironment env = environmentOn(dir, true, true);

        gate().postProcessEnvironment(env, null);

        assertThat(env.getProperty(TelemetryLicenseGate.METRICS, Boolean.class)).isFalse();
        // The point of the split: a Team deployment still ships its traces to its own collector.
        assertThat(env.getProperty(TRACES, Boolean.class)).isTrue();
        // In front of everything, which is what "whatever the setting says" has to mean.
        assertThat(env.getPropertySources().iterator().next().getName())
                .isEqualTo(TelemetryLicenseGate.SOURCE);
        assertThat(logged).hasSize(1);
        assertThat(logged.getFirst())
                .contains(Feature.OTEL_EXPORT.label + " is an Enterprise feature")
                .contains("traces are unaffected");
    }

    @Test
    void on_an_enterprise_license_the_switches_are_left_alone(@TempDir Path dir) throws Exception {
        TestLicenses.installFixture(dir, "enterprise-test.license");
        MockEnvironment env = environmentOn(dir, true, true);

        gate().postProcessEnvironment(env, null);

        assertThat(env.getProperty(TelemetryLicenseGate.METRICS, Boolean.class)).isTrue();
        assertThat(env.getPropertySources().contains(TelemetryLicenseGate.SOURCE)).isFalse();
        assertThat(logged).isEmpty();
    }

    @Test
    void without_a_license_the_switches_are_left_alone(@TempDir Path dir) {
        MockEnvironment env = environmentOn(dir, true, true);

        gate().postProcessEnvironment(env, null);

        assertThat(env.getProperty(TRACES, Boolean.class)).isTrue();
        assertThat(env.getProperty(TelemetryLicenseGate.METRICS, Boolean.class)).isTrue();
        assertThat(logged).isEmpty();
    }

    @Test
    void tracing_alone_never_reads_the_license(@TempDir Path dir) throws Exception {
        // A Team license installed, traces on, metrics off: the gate has nothing to withhold.
        TestLicenses.installFixture(dir, "team-test.license");
        MockEnvironment env = environmentOn(dir, true, false);
        TelemetryLicenseGate gate = new TelemetryLicenseGate(logs, (dataDir, key) -> {
            throw new AssertionError("no license should have been read");
        });

        gate.postProcessEnvironment(env, null);

        assertThat(env.getProperty(TRACES, Boolean.class)).isTrue();
        assertThat(env.getPropertySources().contains(TelemetryLicenseGate.SOURCE)).isFalse();
        assertThat(logged).isEmpty();
    }
}
