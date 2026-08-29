package com.concentus.store;

import com.concentus.policy.OrgPolicy;
import com.concentus.policy.PublishApproval;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two policy-owned records through the real store: a policy keyed by its organization, and
 * an approval keyed by its flow. Against the embedded PostgreSQL like {@link JsonStoreTest}, for
 * the same reason — the claim is that the rows come back whole, not that strings were passed.
 */
class OrgPolicyStoreTest {

    private OrgPolicyStore policies;
    private PublishApprovalStore approvals;

    @BeforeEach
    void freshTables() {
        TestDatabase.reset(TestDatabase.jdbc());
        ObjectMapper mapper = new ObjectMapper();
        policies = new OrgPolicyStore(TestDatabase.jdbc(), mapper);
        approvals = new PublishApprovalStore(TestDatabase.jdbc(), mapper);
        policies.init();
        approvals.init();
    }

    @Test
    void aPolicyRoundTripsUnderItsOrganizationAndASecondSaveReplacesIt() {
        OrgPolicy saved = policies.save(new OrgPolicy("acme", "fprof_reader", true, "acceptEdits", 250.0, true));

        assertThat(saved.id()).isEqualTo("acme");
        assertThat(policies.get("acme")).contains(saved);
        assertThat(policies.get("acme").orElseThrow().maxPermissionMode()).isEqualTo("acceptEdits");
        assertThat(policies.get("acme").orElseThrow().monthlyBudgetUsd()).isEqualTo(250.0);

        policies.save(new OrgPolicy("acme", "", false, "", null, false));

        // One per organization: the second save is an update, not a sibling.
        assertThat(policies.list()).hasSize(1);
        assertThat(policies.get("acme").orElseThrow().requireFacade()).isFalse();
        assertThat(policies.get("acme").orElseThrow().hasBudget()).isFalse();
    }

    @Test
    void anApprovalRoundTripsUnderItsFlowAndCoversExactlyTheTokenItNames() {
        approvals.save(new PublishApproval("flow_1", "tok-a", "admin@acme.test", 1_000L));

        PublishApproval back = approvals.get("flow_1").orElseThrow();
        assertThat(back.approvedBy()).isEqualTo("admin@acme.test");
        assertThat(back.covers("tok-a")).isTrue();
        // A regenerated token is a different door: the old approval says nothing about it.
        assertThat(back.covers("tok-b")).isFalse();

        assertThat(approvals.delete("flow_1")).isTrue();
        assertThat(approvals.get("flow_1")).isEmpty();
    }
}
