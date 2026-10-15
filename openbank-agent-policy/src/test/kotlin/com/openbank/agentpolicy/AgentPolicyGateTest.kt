// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0031 D9 phase 4: [AgentPolicyGate] composes the two deny-by-default layers, unchanged
 * from phase 3's flaky-test-hunter gate. Charter denial short-circuits before the OPA call;
 * an OPA policy deny carries the OPA reason; ANY OPA failure (connectivity, HTTP, parse) is a
 * DENY with reason `pdp_unreachable` — never an exception, never an advisory pass-through,
 * because this gate protects a write path.
 */
class AgentPolicyGateTest {

    private val charterVerifier = mockk<AgentCharterGrantVerifier>()
    private val opa = mockk<OpaAgentDecisionClient>()
    private val gate = AgentPolicyGate(charterVerifier, opa)

    private val input = AgentPolicyConfig.flakyTestHunter().defaultInput()

    @Test
    fun `a chartered grant and an allowing OPA decision allow`() {
        every { charterVerifier.checkGrant(input) } returns CharterGrant.Granted
        coEvery { opa.queryDecision(input) } returns OpaAgentDecision.Allowed

        val decision = runBlocking { gate.check(input) }

        assertThat(decision).isEqualTo(PolicyDecision.Allowed)
    }

    @Test
    fun `a charter denial short-circuits and never queries the sidecar`() {
        every { charterVerifier.checkGrant(input) } returns
            CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED)

        val decision = runBlocking { gate.check(input) }

        assertThat(decision).isEqualTo(PolicyDecision.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED))
        coVerify { opa wasNot Called }
    }

    @Test
    fun `an OPA policy deny carries the OPA reason`() {
        every { charterVerifier.checkGrant(input) } returns CharterGrant.Granted
        coEvery { opa.queryDecision(input) } returns OpaAgentDecision.Denied("no matching allow rule")

        val decision = runBlocking { gate.check(input) }

        assertThat(decision).isEqualTo(
            PolicyDecision.Denied("${AgentPolicyVocabulary.REASON_OPA_DENIED_PREFIX}: no matching allow rule"),
        )
    }

    @Test
    fun `an unreachable sidecar fails closed with pdp_unreachable`() {
        every { charterVerifier.checkGrant(input) } returns CharterGrant.Granted
        coEvery { opa.queryDecision(input) } throws OpaGateException("connection refused")

        val decision = runBlocking { gate.check(input) }

        assertThat(decision).isEqualTo(PolicyDecision.Denied(AgentPolicyVocabulary.REASON_PDP_UNREACHABLE))
    }

    @Test
    fun `a money-path input is checked against its own grant, not the github-pr one`() {
        val moneyPathInput = PolicyDecisionInput(
            tool = "money.repair.propose",
            resource = "payment-instruction",
            tier = "money_path_write",
        )
        every { charterVerifier.checkGrant(moneyPathInput) } returns CharterGrant.Granted
        coEvery { opa.queryDecision(moneyPathInput) } returns OpaAgentDecision.Allowed

        val decision = runBlocking { gate.check(moneyPathInput) }

        assertThat(decision).isEqualTo(PolicyDecision.Allowed)
        verify { charterVerifier.checkGrant(moneyPathInput) }
        coVerify { opa.queryDecision(moneyPathInput) }
    }
}
