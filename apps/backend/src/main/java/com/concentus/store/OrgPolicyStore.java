package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.policy.OrgPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Organization policies, in the shared resources table like every resource — keyed by the
 * organization id rather than a generated one, because there is exactly one per organization
 * and "the policy for org X" is the only question ever asked of this store.
 */
@Component
public class OrgPolicyStore extends JsonStore<OrgPolicy> {

    public OrgPolicyStore(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        // No legacy folder: policies never lived on disk. The prefix is never used either — a
        // policy is always saved under its organization — but the base class asks for one.
        super(jdbc, mapper, OrgPolicy.class, "org-policy", "orgpol_", null, orgContext);
    }

    @Override
    protected String idOf(OrgPolicy p) {
        return p.id();
    }

    @Override
    protected OrgPolicy withId(OrgPolicy p, String id) {
        return p.withId(id);
    }

    @Override
    protected String sortKey(OrgPolicy p) {
        return p.id();
    }
}
