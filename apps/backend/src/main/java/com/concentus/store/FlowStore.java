package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Persists flows as one JSON file per flow under {@code <data-dir>/flows}. */
@Component
public class FlowStore extends JsonStore<FlowGraph> {

    public FlowStore(@Value("${app.data-dir}") String dataDir, ObjectMapper mapper) {
        super(Path.of(dataDir, "flows"), mapper, FlowGraph.class, "flow_");
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
