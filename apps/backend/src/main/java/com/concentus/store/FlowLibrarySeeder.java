package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Installs the bundled starter flows into the flow store.
 *
 * <p>A flow shipped as a classpath resource is invisible: the Flows page reads the store, so a
 * template nobody has copied there does not exist as far as the product is concerned. This copies
 * them in on startup so they appear, ready to open on the canvas and edit like any other flow.
 *
 * <p>Seeding is recorded in a marker file rather than keyed on "the flows directory is empty".
 * Empty-directory seeding would skip the starter flows entirely for anyone who already had a flow
 * — which is every existing install. Recording what has been installed also means <b>deleting a
 * seeded flow makes it stay deleted</b>: it is a starter, not something the app keeps reinstating
 * against the user's wishes.
 */
@Component
public class FlowLibrarySeeder {

    private static final Logger log = LoggerFactory.getLogger(FlowLibrarySeeder.class);

    /** One seeded flow id per line. Lives beside the flows so it travels with the data directory. */
    private static final String MARKER = ".seeded-flows";

    private final Path flowsDir;
    private final FlowStore flows;
    private final ObjectMapper mapper;

    public FlowLibrarySeeder(@Value("${app.data-dir}") String dataDir, FlowStore flows,
                             ObjectMapper mapper) {
        this.flowsDir = Path.of(dataDir, "flows");
        this.flows = flows;
        this.mapper = mapper;
    }

    @PostConstruct
    void seed() {
        try {
            Files.createDirectories(flowsDir);
            Set<String> alreadySeeded = readMarker();
            Set<String> seeded = new LinkedHashSet<>(alreadySeeded);

            for (Resource resource : bundled()) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                try (InputStream in = resource.getInputStream()) {
                    FlowGraph flow = mapper.readValue(in.readAllBytes(), FlowGraph.class);
                    if (flow.id() == null || flow.id().isBlank()) {
                        log.warn("Skipping bundled flow {} — it has no id.", filename);
                        continue;
                    }
                    // Two independent reasons to leave it alone: the user already has it (editing
                    // it would discard their changes), or they installed and then deleted it.
                    if (alreadySeeded.contains(flow.id()) || flows.get(flow.id()).isPresent()) {
                        seeded.add(flow.id());
                        continue;
                    }
                    flows.save(flow);
                    seeded.add(flow.id());
                    log.info("Installed the bundled flow '{}' ({}).", flow.name(), flow.id());
                } catch (Exception e) {
                    log.warn("Could not install bundled flow {}: {}", filename, e.getMessage());
                }
            }
            writeMarker(seeded);
        } catch (IOException e) {
            // A starter flow failing to install must never stop the app from serving real ones.
            log.warn("Could not seed bundled flows: {}", e.getMessage());
        }
    }

    private Resource[] bundled() throws IOException {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath*:library-flows/*.json");
    }

    private Set<String> readMarker() {
        Path marker = flowsDir.resolve(MARKER);
        if (!Files.isRegularFile(marker)) return Set.of();
        try {
            Set<String> ids = new LinkedHashSet<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) ids.add(line.trim());
            }
            return ids;
        } catch (IOException e) {
            return Set.of();
        }
    }

    private void writeMarker(Set<String> ids) {
        if (ids.isEmpty()) return;
        try {
            Files.write(flowsDir.resolve(MARKER), List.copyOf(ids), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Worst case the flow is re-offered next start, which is recoverable; failing startup
            // over a bookkeeping file is not.
            log.debug("Could not record seeded flows: {}", e.getMessage());
        }
    }
}
