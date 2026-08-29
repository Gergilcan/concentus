package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowEvalCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * The evaluation cases of every flow, in the shared resources table like every other definition.
 *
 * <p>One kind for all flows rather than a table per flow: a case is a small record that belongs
 * to a flow id, and the store already knows how to keep id-keyed JSON. Scoping to a flow is a
 * filter over the list, which is tens of rows, not a report.
 */
@Component
public class EvalDatasetStore extends JsonStore<FlowEvalCase> {

    public EvalDatasetStore(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        super(jdbc, mapper, FlowEvalCase.class, "eval-case", "evc_", null, orgContext);
    }

    @Override
    protected String idOf(FlowEvalCase c) {
        return c.id();
    }

    @Override
    protected FlowEvalCase withId(FlowEvalCase c, String id) {
        return new FlowEvalCase(id, c.flowId(), c.name(), c.input(), c.expected(), c.judge(), c.createdAt());
    }

    @Override
    protected String sortKey(FlowEvalCase c) {
        return c.name();
    }

    /** This flow's cases, oldest first — the order they were written in is the order people read them in. */
    public List<FlowEvalCase> listForFlow(String flowId) {
        if (flowId == null) return List.of();
        return list().stream()
                .filter(c -> flowId.equals(c.flowId()))
                .sorted(Comparator.comparingLong(FlowEvalCase::createdAt))
                .toList();
    }
}
