package com.concentus.model;

/** A reusable database connection (no query — the query stays on the SQL node). */
public record DatabaseDef(String id, String label, String jdbcUrl, String username, String passwordEnv) {

    public DatabaseDef withId(String newId) {
        return new DatabaseDef(newId, label, jdbcUrl, username, passwordEnv);
    }
}
