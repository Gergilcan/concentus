package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.DatabaseDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Reusable database connection definitions, in the database. Old files are imported once. */
@Component
public class DatabaseStore extends JsonStore<DatabaseDef> {

    public DatabaseStore(JdbcTemplate jdbc, @Value("${app.data-dir}") String dataDir, ObjectMapper mapper,
                         OrgContext orgContext) {
        super(jdbc, mapper, DatabaseDef.class, "database", "db_", Path.of(dataDir, "databases"), orgContext);
    }

    @Override
    protected String idOf(DatabaseDef d) {
        return d.id();
    }

    @Override
    protected DatabaseDef withId(DatabaseDef d, String id) {
        return d.withId(id);
    }

    @Override
    protected String sortKey(DatabaseDef d) {
        return d.label();
    }
}
