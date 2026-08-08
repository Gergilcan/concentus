package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
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
 * <p>Seeding is recorded rather than keyed on "the store is empty". The latter would skip the
 * starter flows entirely for anyone who already had a flow
 * — which is every existing install. Recording what has been installed also means <b>deleting a
 * seeded flow makes it stay deleted</b>: it is a starter, not something the app keeps reinstating
 * against the user's wishes.
 */
@Component
public class FlowLibrarySeeder {

    private static final Logger log = LoggerFactory.getLogger(FlowLibrarySeeder.class);

    /**
     * Which starter flows have been installed, recorded in the database beside the flows.
     *
     * <p>This used to be a file next to them. Now that flows are rows it has to follow them: with
     * an external database shared between installs, a marker left on one machine would let a
     * second install re-offer a starter flow the first one deleted — exactly the behaviour this
     * record exists to prevent.
     */
    private static final String MARKER_KIND = "meta";
    private static final String MARKER_ID = "seeded-flows";

    private final JdbcTemplate jdbc;
    private final FlowStore flows;
    private final ObjectMapper mapper;

    public FlowLibrarySeeder(JdbcTemplate jdbc, FlowStore flows, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.flows = flows;
        this.mapper = mapper;
    }

    @PostConstruct
    void seed() {
        try {
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
        try {
            return jdbc.query("select json from resources where kind = ? and id = ?",
                            (rs, i) -> rs.getString("json"), MARKER_KIND, MARKER_ID)
                    .stream().findFirst()
                    .map(this::parseIds)
                    .orElse(Set.of());
        } catch (RuntimeException e) {
            // "Nothing recorded" is the safe reading: the worst case is a starter flow being
            // offered again, and the flows.get() check still stops it overwriting a real one.
            return Set.of();
        }
    }

    private Set<String> parseIds(String json) {
        try {
            return new LinkedHashSet<>(mapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void writeMarker(Set<String> ids) {
        if (ids.isEmpty()) return;
        try {
            jdbc.update("""
                    insert into resources (kind, id, sort_key, json, updated_at)
                    values (?, ?, ?, ?, ?)
                    on conflict (kind, id) do update
                       set json = excluded.json, updated_at = excluded.updated_at
                    """, MARKER_KIND, MARKER_ID, MARKER_ID,
                    mapper.writeValueAsString(List.copyOf(ids)), System.currentTimeMillis());
        } catch (Exception e) {
            // Worst case the flow is re-offered next start, which is recoverable; failing startup
            // over bookkeeping is not.
            log.debug("Could not record seeded flows: {}", e.getMessage());
        }
    }
}
