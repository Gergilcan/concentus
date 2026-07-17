package com.concentus.store;

import com.concentus.model.DatabaseDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Reusable database connection definitions under {@code <data-dir>/databases}. */
@Component
public class DatabaseStore extends JsonStore<DatabaseDef> {

    public DatabaseStore(@Value("${app.data-dir}") String dataDir, ObjectMapper mapper) {
        super(Path.of(dataDir, "databases"), mapper, DatabaseDef.class, "db_");
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
