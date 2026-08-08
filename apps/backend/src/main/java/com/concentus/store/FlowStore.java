package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Flows, in the database. Anything left in {@code <data-dir>/flows} is imported once. */
@Component
public class FlowStore extends JsonStore<FlowGraph> {

    public FlowStore(JdbcTemplate jdbc, @Value("${app.data-dir}") String dataDir, ObjectMapper mapper) {
        super(jdbc, mapper, FlowGraph.class, "flow", "flow_", Path.of(dataDir, "flows"));
    }

    @Override
    protected String idOf(FlowGraph f) {
        return f.id();
    }

    @Override
    protected FlowGraph withId(FlowGraph f, String id) {
        return f.withId(id);
    }

    @Override
    protected String sortKey(FlowGraph f) {
        return f.name();
    }
}
