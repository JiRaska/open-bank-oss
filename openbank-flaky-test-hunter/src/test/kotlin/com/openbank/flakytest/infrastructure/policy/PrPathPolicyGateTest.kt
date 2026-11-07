// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-0031 D9 phase 3: [PrPathPolicyGate] composes the two deny-by-default layers. Charter
 * denial short-circuits before the OPA call; an OPA policy deny carries the OPA reason; ANY
 * OPA failure (connectivity, HTTP, parse) is a DENY with reason `pdp_unreachable` — never an
 * exception, never an advisory pass-through, because this gate protects a write path.
 */
class PrPathPolicyGateTest {

    private val config = mockk<FlakyTestHunterConfig>()
    private val gateConfig = mockk<FlakyTestHunterConfig.PolicyGate>()
    private val charterVerifier = mockk<AgentCharterGrantVerifier>()
    private val opa = mockk<OpaAgentDecisionClient>()
    private val gate = PrPathPolicyGate(config, charterVerifier, opa)

    private val finding = FlakyTestFinding(
        id = "abcdef1234567890",
        checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.now(),
        title = "SomeTest.kt:12 uses the unsafe '= runBlocking {' form without ': Unit'",
        component = "openbank-flaky-test-hunter/src/test/kotlin/com/openbank/flakytest/SomeTest.kt",
        filePath = "openbank-flaky-test-hunter/src/test/kotlin/com/openbank/flakytest/SomeTest.kt",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
    )

    private fun gateEnabled(enabled: Boolean = true) {
        every { config.policyGate() } returns gateConfig
        every { gateConfig.enabled() } returns enabled
    }

    @Test
    fun `a disabled gate denies PR writes without consulting the charter or the sidecar`() {
        gateEnabled(enabled = false)

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Denied(PrPathGateVocabulary.REASON_POLICY_GATE_DISABLED))
        verify { charterVerifier wasNot Called }
        coVerify { opa wasNot Called }
    }

    @Test
    fun `an enabled gate with a chartered grant and an allowing OPA decision allows`() {
        gateEnabled()
        every { charterVerifier.checkPrOpenGrant() } returns CharterGrant.Granted
        coEvery { opa.queryPrOpenDecision() } returns OpaAgentDecision.Allowed

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Allowed("flaky-test-hunter/unit-return-abcdef12"))
    }

    @Test
    fun `a charter denial short-circuits and never queries the sidecar`() {
        gateEnabled()
        every { charterVerifier.checkPrOpenGrant() } returns
            CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED)

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED))
        coVerify { opa wasNot Called }
    }

    @Test
    fun `an OPA policy deny carries the OPA reason`() {
        gateEnabled()
        every { charterVerifier.checkPrOpenGrant() } returns CharterGrant.Granted
        coEvery { opa.queryPrOpenDecision() } returns OpaAgentDecision.Denied("no matching allow rule")

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(
            PrPathDecision.Denied("${PrPathGateVocabulary.REASON_OPA_DENIED_PREFIX}: no matching allow rule"),
        )
    }

    @Test
    fun `an unreachable sidecar fails closed with pdp_unreachable`() {
        gateEnabled()
        every { charterVerifier.checkPrOpenGrant() } returns CharterGrant.Granted
        coEvery { opa.queryPrOpenDecision() } throws OpaGateException("connection refused")

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Denied(PrPathGateVocabulary.REASON_PDP_UNREACHABLE))
    }
}
