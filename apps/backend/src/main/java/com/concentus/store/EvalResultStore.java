package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowEvalResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Every evaluation ever run, kept: a score is only worth something next to the previous one.
 *
 * <p>A result is rewritten as its cases finish, so the same row goes from "running, 0 of 10" to
 * "done, 8 of 10" — which is what lets the UI poll one id instead of holding a connection open
 * for the twenty minutes ten agent runs can take.
 */
@Component
public class EvalResultStore extends JsonStore<FlowEvalResult> {

    public EvalResultStore(JdbcTemplate jdbc, ObjectMapper mapper, OrgContext orgContext) {
        super(jdbc, mapper, FlowEvalResult.class, "eval-result", "evr_", null, orgContext);
    }

    @Override
    protected String idOf(FlowEvalResult r) {
        return r.id();
    }

    @Override
    protected FlowEvalResult withId(FlowEvalResult r, String id) {
        return new FlowEvalResult(id, r.flowId(), r.flowVersion(), r.startedAt(), r.finishedAt(),
                r.status(), r.cases(), r.passed(), r.total());
    }

    @Override
    protected String sortKey(FlowEvalResult r) {
        return String.valueOf(r.startedAt());
    }

    /** This flow's evaluations, newest first: the latest score is the one being asked about. */
    public List<FlowEvalResult> listForFlow(String flowId) {
        if (flowId == null) return List.of();
        return list().stream()
                .filter(r -> flowId.equals(r.flowId()))
                .sorted(Comparator.comparingLong(FlowEvalResult::startedAt).reversed())
                .toList();
    }
}
