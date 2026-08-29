package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.SkillDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Agent Skills, in the database like every other resource record. */
@Component
public class SkillStore extends JsonStore<SkillDef> {

    public SkillStore(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        super(jdbc, mapper, SkillDef.class, "skill", "skill_", null, orgContext);
    }

    @Override
    protected String idOf(SkillDef s) {
        return s.id();
    }

    @Override
    protected SkillDef withId(SkillDef s, String id) {
        return new SkillDef(id, s.name(), s.description(), s.files());
    }

    @Override
    protected String sortKey(SkillDef s) {
        return s.name();
    }
}
