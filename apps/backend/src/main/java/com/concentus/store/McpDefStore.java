package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.McpDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Reusable MCP server definitions, in the database. Old files are imported once. */
@Component
public class McpDefStore extends JsonStore<McpDef> {

    public McpDefStore(JdbcTemplate jdbc, @Value("${app.data-dir}") String dataDir, ObjectMapper mapper,
                       OrgContext orgContext) {
        super(jdbc, mapper, McpDef.class, "mcp", "mcp_", Path.of(dataDir, "mcp-servers"), orgContext);
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
