package com.concentus.model;

/** A reusable database connection (no query — the query stays on the SQL node). */
public record DatabaseDef(String id, String label, String jdbcUrl, String username, String credentialId,
                          String groupId) {

    /** The pre-groups shape: a connection the whole organization sees. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public DatabaseDef(String id, String label, String jdbcUrl, String username, String credentialId) {
        this(id, label, jdbcUrl, username, credentialId, null);
    }

    public DatabaseDef withId(String newId) {
        return new DatabaseDef(newId, label, jdbcUrl, username, credentialId, groupId);
    }
}
