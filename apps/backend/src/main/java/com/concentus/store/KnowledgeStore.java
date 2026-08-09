package com.concentus.store;

import com.concentus.model.KnowledgeDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Knowledge base definitions, in the database like every other resource record.
 *
 * <p>No legacy directory to import: knowledge bases never existed as files, so the inherited
 * migration path is simply given nothing to find.
 */
@Component
public class KnowledgeStore extends JsonStore<KnowledgeDef> {

    public KnowledgeStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper, KnowledgeDef.class, "knowledge", "kb_", null);
    }

    @Override
    protected String idOf(KnowledgeDef k) {
        return k.id();
    }

    @Override
    protected KnowledgeDef withId(KnowledgeDef k, String id) {
        return new KnowledgeDef(id, k.name(), k.description());
    }

    @Override
    protected String sortKey(KnowledgeDef k) {
        return k.name();
    }
}
