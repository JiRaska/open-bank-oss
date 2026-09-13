// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DelegationLifecycleApprovalOpenApiTest {
    @Test
    fun `contract publishes read list detail and dark launched mutation semantics`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

        assertThat(contract).contains("/api/v1/delegations/approvals:")
        assertThat(contract).contains("/api/v1/delegations/approvals/{approvalId}:")
        assertThat(contract).contains("/api/v1/delegations/approvals/{approvalId}/decision:")
        assertThat(contract).contains("DelegationLifecycleApprovalResponse:")
        assertThat(contract).contains("Approval execution unavailable, or conflicting terminal decision")
        assertThat(contract).contains("revision-safe lifecycle seam")
    }

    @Test
    fun `contract pins the negative cases — a wrong or missing identity is refused`() {
        val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

        // ADR-0279 #3: the contract must say what a caller without standing gets, or the
        // provider could stop enforcing and every consumer stays green. The decision endpoint
        // answers 403 to self-decision (the maker may never be the checker) and 404 where
        // the approval is not the caller's to see; the read endpoints declare 403 too.
        assertThat(contract).contains("'403'")
        assertThat(contract).contains("Self-decision")
        assertThat(contract).contains("'404'")
        assertThat(contract).contains("Mutation capability is disabled")
    }
}
