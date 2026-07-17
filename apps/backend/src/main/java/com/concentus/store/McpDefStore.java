package com.concentus.store;

import com.concentus.model.McpDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Reusable MCP server definitions under {@code <data-dir>/mcp-servers}. */
@Component
public class McpDefStore extends JsonStore<McpDef> {

    public McpDefStore(@Value("${app.data-dir}") String dataDir, ObjectMapper mapper) {
        super(Path.of(dataDir, "mcp-servers"), mapper, McpDef.class, "mcp_");
    }

    @Override
    protected String idOf(McpDef m) {
        return m.id();
    }

    @Override
    protected McpDef withId(McpDef m, String id) {
        return m.withId(id);
    }

    @Override
    protected String sortKey(McpDef m) {
        return m.name();
    }
}
