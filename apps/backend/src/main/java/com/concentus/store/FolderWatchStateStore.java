package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.FolderWatchState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Folder-watch memory, one row per flow in the shared resources table.
 *
 * <p>A JSON record rather than a table of its own: it is one timestamp per flow, read once at the
 * first poll after a start and written once per run that fires, and a migration for that would
 * be more schema than data. No legacy folder — this store was born on the database.
 */
@Component
public class FolderWatchStateStore extends JsonStore<FolderWatchState> {

    public FolderWatchStateStore(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        super(jdbc, mapper, FolderWatchState.class, "watch", "watch_", null, orgContext);
    }

    @Override
    protected String idOf(FolderWatchState state) {
        return state.id();
    }

    @Override
    protected FolderWatchState withId(FolderWatchState state, String id) {
        return state.withId(id);
    }

    @Override
    protected String sortKey(FolderWatchState state) {
        return state.id();
    }
}
