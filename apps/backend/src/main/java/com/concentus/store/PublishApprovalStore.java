package com.concentus.store;

import com.concentus.policy.PublishApproval;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Publish approvals, keyed by flow id: one flow has at most one approved token at a time. */
@Component
public class PublishApprovalStore extends JsonStore<PublishApproval> {

    public PublishApprovalStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper, PublishApproval.class, "publish-approval", "pubapp_", null);
    }

    @Override
    protected String idOf(PublishApproval a) {
        return a.flowId();
    }

    @Override
    protected PublishApproval withId(PublishApproval a, String id) {
        return a.withFlowId(id);
    }

    @Override
    protected String sortKey(PublishApproval a) {
        return a.flowId();
    }
}
