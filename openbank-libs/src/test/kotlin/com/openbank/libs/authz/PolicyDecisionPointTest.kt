// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.authz

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the [PolicyDecisionPoint] contract so future implementations cannot
 * silently drift. The two stub implementations are also the test fixtures
 * for every other service's `@Authorize`-decorated test — so a regression
 * here would break the libs test ergonomics promise (ADR-0034 D3).
 */
class PolicyDecisionPointTest {

    private val sampleQuery = AuthzQuery(
        principal = Principal(id = "u-7", type = "HUMAN", roles = listOf("ROLE_OPERATOR")),
        action = "party.update",
        resource = ResourceRef(type = "party", id = "p-123"),
        attributes = mapOf("client-ip" to "10.0.0.1"),
    )

    @Test
    fun `allow-all returns allow with grep-able test-stub reason`(): Unit = runBlocking {
        val pdp: PolicyDecisionPoint = AllowAllPolicyDecisionPoint()
        val decision = pdp.allow(sampleQuery)
        assertThat(decision.allow).isTrue()
        assertThat(decision.reason).isEqualTo("test-stub")
        assertThat(decision.policyVersion).isEqualTo("allow-all")
    }

    @Test
    fun `deny-all returns deny with kill-switch reason`(): Unit = runBlocking {
        val pdp: PolicyDecisionPoint = DenyAllPolicyDecisionPoint()
        val decision = pdp.allow(sampleQuery)
        assertThat(decision.allow).isFalse()
        assertThat(decision.reason).isEqualTo("kill-switch-engaged")
    }

    @Test
    fun `query shape carries principal type so audit can separate humans from AI agents`() {
        val human = AuthzQuery(
            principal = Principal(id = "u-1", type = "HUMAN"),
            action = "x.read",
        )
        val agent = AuthzQuery(
            principal = Principal(id = "agent-onboarding", type = "AI_AGENT"),
            action = "x.read",
        )
        assertThat(human.principal.type).isEqualTo("HUMAN")
        assertThat(agent.principal.type).isEqualTo("AI_AGENT")
        // The two queries are otherwise structurally identical — which is the
        // ADR-0034 point: REST and MCP planes go through ONE decision shape.
        assertThat(human.copy(principal = agent.principal)).isEqualTo(agent)
    }

    @Test
    fun `resource is optional for non-scoped actions`(): Unit = runBlocking {
        val pdp = AllowAllPolicyDecisionPoint()
        val nonScoped = AuthzQuery(
            principal = Principal(id = "ops", type = "HUMAN"),
            action = "system.snapshot",
            resource = null,
        )
        // Must not throw — null resource is legitimate.
        val decision = pdp.allow(nonScoped)
        assertThat(decision.allow).isTrue()
    }
}
