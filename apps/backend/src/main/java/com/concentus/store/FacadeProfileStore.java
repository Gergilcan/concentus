package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.FacadeProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Facade profiles for independent workers, in the shared resources table like every resource. */
@Component
public class FacadeProfileStore extends JsonStore<FacadeProfile> {

    public FacadeProfileStore(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        super(jdbc, mapper, FacadeProfile.class, "facade-profile", "fprof_", null, orgContext);
    }

    @Override
    protected String idOf(FacadeProfile p) {
        return p.id();
    }

    @Override
    protected FacadeProfile withId(FacadeProfile p, String id) {
        // The canonical constructor, and that is load-bearing: every save goes through here, and
        // the six-argument shortcut this used to call silently dropped readAlso from every stored
        // profile — the same trap FlowGraph.withId documents.
        return new FacadeProfile(id, p.name(), p.description(), p.tools(), p.readOnly(), p.dryRun(), p.readAlso());
    }

    @Override
    protected String sortKey(FacadeProfile p) {
        return p.name();
    }
}
