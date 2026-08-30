package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

/**
 * The resource stores, wired and initialised outside a container, for tests in other packages.
 *
 * <p>Every store's {@code init()} is package-private — Spring calls it — so a test that lives
 * beside the marketplace rather than beside the stores comes through here to get one that has
 * probed its table.
 */
public final class TestStores {

    private TestStores() {
    }

    public static McpDefStore mcpDefs(JdbcTemplate jdbc, Path dataDir, ObjectMapper mapper, OrgContext orgContext) {
        McpDefStore store = new McpDefStore(jdbc, dataDir.toString(), mapper, orgContext);
        store.init();
        return store;
    }

    public static AgentLibraryStore agents(JdbcTemplate jdbc, Path dataDir, ObjectMapper mapper, OrgContext orgContext) {
        AgentLibraryStore store = new AgentLibraryStore(jdbc, dataDir.resolve("agents").toString(),
                dataDir.toString(), mapper, orgContext);
        store.init();
        return store;
    }

    public static FacadeProfileStore facades(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        FacadeProfileStore store = new FacadeProfileStore(jdbc, mapper, orgContext);
        store.init();
        return store;
    }

    public static SkillStore skills(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        SkillStore store = new SkillStore(jdbc, mapper, orgContext);
        store.init();
        return store;
    }

    public static FlowStore flows(JdbcTemplate jdbc, Path dataDir, ObjectMapper mapper, OrgContext orgContext) {
        FlowStore store = new FlowStore(jdbc, dataDir.toString(), mapper, orgContext);
        store.init();
        return store;
    }

    public static FlowVersionStore flowVersions(JdbcTemplate jdbc, ObjectMapper mapper) {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper);
        store.init();
        return store;
    }
}
