// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the [PolicyDecisionPoint] contract so future implementations cannot
 * silently drift. The allow-all test fixture lives in `openbank-libs-testing`
 * now (`com.openbank.libs.testing.authz.AllowAllPolicyDecisionPoint` —
 * `AllowAllPolicyDecisionPointTest` there pins its contract); this module
 * keeps only [DenyAllPolicyDecisionPoint], the genuinely-production
 * kill-switch implementation, plus the shape tests that don't depend on
 * either stub.
 */
class PolicyDecisionPointTest {

    private val sampleQuery = AuthzQuery(
        principal = Principal(id = "u-7", type = "HUMAN", roles = listOf("ROLE_OPERATOR")),
        action = "party.update",
        resource = ResourceRef(type = "party", id = "p-123"),
        attributes = mapOf("client-ip" to "10.0.0.1"),
    )

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
}
