// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import com.openbank.libs.decision.PolicyAttribute
import com.openbank.libs.decision.PolicyEvaluator
import com.openbank.libs.decision.PolicyOperator
import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.decision.PolicyValue
import com.openbank.libs.lending.compliance.AprDisclosure
import com.openbank.libs.lending.compliance.CompliancePack
import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.DisclosureStage
import com.openbank.libs.lending.compliance.PackDisclosure
import com.openbank.libs.lending.compliance.PackProductType
import com.openbank.libs.lending.compliance.TerminationGround
import com.openbank.libs.lending.compliance.TerminationRules
import com.openbank.libs.lending.origination.OriginationState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Golden vectors for the two libs-lending hashes persisted with a credit decision: the policy
 * input-snapshot hash (ADR-0214 D2) and the compliance-pack content hash. Both are stored next
 * to the decision they explain, so how the digest bytes are rendered must never move.
 *
 * Every expected string was computed outside this codebase (Python `hashlib` over the same
 * canonical rendering). The snapshot-hash inputs were chosen so the first digest byte is `0x00`,
 * `0x0f` and `0xff` — a dropped leading zero and a sign-extended byte are the two ways a hex
 * renderer goes wrong.
 */
class HexOutputGoldenVectorTest {

    private fun snapshot(channel: String) = PolicyEvaluator.inputSnapshotHash(
        mapOf(
            PolicyAttribute.CHANNEL to PolicyValue.Text(channel),
            PolicyAttribute.AGE_YEARS to PolicyValue.Numeric(BigDecimal("42.0")),
        ),
    )

    @Test
    fun `PolicyEvaluator inputSnapshotHash`() {
        assertThat(PolicyEvaluator.inputSnapshotHash(emptyMap()))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(snapshot("web-120")).isEqualTo("0040fac8ec94cdd9a1aa40c026d3574e05c559771f799adaad35d8e9544274aa")
        assertThat(snapshot("web-305")).isEqualTo("0fb33ec236b31f685b60198fd47217f9e621e6c90f1540827befc00e99d06d88")
        assertThat(snapshot("web-23")).isEqualTo("ff82d47d6101f0fd473b2fb57f14d43dfcfbe6c5831d551042c622584da302ea")
    }

    @Test
    fun `CompliancePackCompiler contentHash`() {
        val pack = CompliancePack(
            jurisdiction = "CZ",
            productType = PackProductType.CONSUMER_CREDIT,
            version = 3,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = LocalDate.of(2027, 1, 1),
            requiredSteps = setOf(OriginationState.REFLECTION_PERIOD, OriginationState.DOCS_REQUIRED),
            coolingOffDays = 14,
            reflectionPeriodDays = 7,
            aprDisclosure = AprDisclosure("RPSN", "cs-CZ"),
            earlyRepaymentCompensationCap = BigDecimal("0.010"),
            terminationRules = TerminationRules(30, setOf(TerminationGround.FRAUD, TerminationGround.DEFAULT_DPD), 90),
            disclosures = listOf(
                PackDisclosure("secci", "lending/secci", setOf("en", "cs"), true, DisclosureStage.PRE_CONTRACTUAL),
            ),
            mandatoryChecks = listOf(
                PolicyRule("dsti-cap", PolicyAttribute.DSTI, PolicyOperator.LTE, threshold = BigDecimal("0.45")),
                PolicyRule("age", PolicyAttribute.RESIDENCY, PolicyOperator.IN, values = setOf("SK", "CZ"), band = "A"),
            ),
        )
        assertThat(CompliancePackCompiler.compile(pack).contentHash).isEqualTo(CONTENT_HASH)
        assertThat(CompliancePackCompiler.compile(pack.copy(version = 4)).contentHash).isEqualTo(CONTENT_HASH_V4)
    }

    private companion object {
        const val CONTENT_HASH = "c56e03b55f5766e99c089cd627564a84b4c74814c8c70c3e0d9576631cf878dc"
        const val CONTENT_HASH_V4 = "94b9b4425f9acb08cd057edf222604d5a9c0071da0e92b457b8e452b720eee30"
    }
}
