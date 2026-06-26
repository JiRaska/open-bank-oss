// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.policy

import com.openbank.agent.domain.policy.PolicyQuery
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PolicyDecisionPointTest {

    private val query = PolicyQuery(agent = "ledger-domain-engineer", tool = "get_account", resource = "acc-1")

    @Test
    fun `deny-by-default PDP denies everything`() {
        val decision = DenyByDefaultPolicyDecisionPoint().evaluate(query)

        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).contains("deny-by-default")
        assertThat(decision.agent).isEqualTo("ledger-domain-engineer")
    }

    @Test
    fun `OPA PDP maps an allow decision`() {
        val opa = mockk<OpaClient>()
        every { opa.decision(any()) } returns OpaResponse(OpaDecision(allow = true, reason = "allowed by charter"))
        val pdp = OpaPolicyDecisionPoint().apply { this.opa = opa }

        val decision = pdp.evaluate(query)

        assertThat(decision.allow).isTrue()
        assertThat(decision.reason).isEqualTo("allowed by charter")
    }

    @Test
    fun `OPA PDP fails closed when the result is missing`() {
        val opa = mockk<OpaClient>()
        every { opa.decision(any()) } returns OpaResponse(result = null)
        val pdp = OpaPolicyDecisionPoint().apply { this.opa = opa }

        val decision = pdp.evaluate(query)

        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).contains("no decision")
    }

    @Test
    fun `OPA PDP fails closed when the sidecar is unreachable`() {
        val opa = mockk<OpaClient>()
        every { opa.decision(any()) } throws RuntimeException("connection refused")
        val pdp = OpaPolicyDecisionPoint().apply { this.opa = opa }

        val decision = pdp.evaluate(query)

        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).contains("fail-closed")
    }
}
