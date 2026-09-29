// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.decision

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Covers ADR-0213: table order, fail-closed semantics, reason codes, versions and the input hash. */
class PolicyEvaluatorTest {

    private val asOf: LocalDate = LocalDate.parse("2026-07-29")

    private fun exclusionTable(effectiveFrom: LocalDate = LocalDate.parse("2026-01-01")) = PolicyTable(
        kind = PolicyTableKind.EXCLUSION,
        name = "exclusion",
        version = 1,
        effectiveFrom = effectiveFrom,
        rules = listOf(
            PolicyRule(
                id = "ex-bureau-f",
                attribute = PolicyAttribute.BUREAU_SCORE_BAND,
                operator = PolicyOperator.EQ,
                values = setOf("F"),
                detail = "bureau band F is an automatic exclusion",
            ),
            PolicyRule(
                id = "ex-jurisdiction",
                attribute = PolicyAttribute.JURISDICTION,
                operator = PolicyOperator.NOT_IN,
                values = setOf("CZ", "DE", "SK"),
                detail = "unsupported jurisdiction",
            ),
        ),
    )

    private fun eligibilityTable() = PolicyTable(
        kind = PolicyTableKind.ELIGIBILITY,
        name = "eligibility",
        version = 3,
        effectiveFrom = LocalDate.parse("2026-01-01"),
        rules = listOf(
            PolicyRule("el-age", PolicyAttribute.AGE_YEARS, PolicyOperator.GTE, threshold = BigDecimal("18")),
            PolicyRule("el-residency", PolicyAttribute.RESIDENCY, PolicyOperator.IN, values = setOf("CZ", "DE", "SK")),
        ),
    )

    private fun affordabilityTable() = PolicyTable(
        kind = PolicyTableKind.AFFORDABILITY,
        name = "affordability",
        version = 2,
        effectiveFrom = LocalDate.parse("2026-01-01"),
        rules = listOf(
            PolicyRule("af-dsti", PolicyAttribute.DSTI, PolicyOperator.LTE, threshold = BigDecimal("0.45")),
            PolicyRule("af-dti", PolicyAttribute.DTI, PolicyOperator.LTE, threshold = BigDecimal("8")),
        ),
    )

    private fun pricingTable() = PolicyTable(
        kind = PolicyTableKind.PRICING_BAND,
        name = "pricing",
        version = 5,
        effectiveFrom = LocalDate.parse("2026-01-01"),
        rules = listOf(
            PolicyRule(
                "pr-a",
                PolicyAttribute.BUREAU_SCORE_BAND,
                PolicyOperator.EQ,
                values = setOf("A"),
                band = "PRIME",
            ),
            PolicyRule(
                "pr-b",
                PolicyAttribute.BUREAU_SCORE_BAND,
                PolicyOperator.EQ,
                values = setOf("B"),
                band = "STANDARD",
            ),
            PolicyRule(
                "pr-c",
                PolicyAttribute.BUREAU_SCORE_BAND,
                PolicyOperator.EQ,
                values = setOf("C"),
                band = "SUBPRIME",
            ),
        ),
    )

    private fun bundle() =
        PolicyBundle(listOf(exclusionTable(), eligibilityTable(), affordabilityTable(), pricingTable()))

    private fun application(
        band: String = "A",
        age: String = "35",
        dsti: String = "0.30",
        dti: String = "4",
        residency: String = "CZ",
        jurisdiction: String = "CZ",
    ) = PolicyApplication(
        attributes = mapOf(
            PolicyAttribute.BUREAU_SCORE_BAND to PolicyValue.Text(band),
            PolicyAttribute.AGE_YEARS to PolicyValue.Numeric(BigDecimal(age)),
            PolicyAttribute.DSTI to PolicyValue.Numeric(BigDecimal(dsti)),
            PolicyAttribute.DTI to PolicyValue.Numeric(BigDecimal(dti)),
            PolicyAttribute.RESIDENCY to PolicyValue.Text(residency),
            PolicyAttribute.JURISDICTION to PolicyValue.Text(jurisdiction),
        ),
        asOf = asOf,
    )

    @Test
    fun `all rules pass yields Approve with band, versions, matched rules and input hash`() {
        val decision = PolicyEvaluator.evaluate(application(), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Approve::class.java)
        decision as PolicyDecision.Approve
        assertThat(decision.priceBand).isEqualTo("PRIME")
        assertThat(decision.evaluation.policyVersions).containsEntry(PolicyTableKind.ELIGIBILITY, 3)
            .containsEntry(PolicyTableKind.AFFORDABILITY, 2)
            .containsEntry(PolicyTableKind.PRICING_BAND, 5)
        assertThat(decision.evaluation.matchedRuleIds).contains("pr-a")
        assertThat(decision.evaluation.reasons).isEmpty()
        assertThat(decision.evaluation.inputSnapshotHash).hasSize(64)
    }

    @Test
    fun `exclusion match declines with the firing rule id`() {
        val decision = PolicyEvaluator.evaluate(application(band = "F"), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Decline::class.java)
        assertThat(decision.evaluation.reasons.single().code).isEqualTo(PolicyReasonCode.EXCLUSION_MATCHED)
        assertThat(decision.evaluation.reasons.single().ruleId).isEqualTo("ex-bureau-f")
    }

    @Test
    fun `unsupported jurisdiction declines via NOT_IN exclusion`() {
        val decision = PolicyEvaluator.evaluate(application(jurisdiction = "US"), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Decline::class.java)
        assertThat(decision.evaluation.reasons.single().ruleId).isEqualTo("ex-jurisdiction")
    }

    @Test
    fun `eligibility failure declines and never reaches pricing (pricing cannot flip a decline)`() {
        val decision = PolicyEvaluator.evaluate(application(age = "17", band = "A"), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Decline::class.java)
        assertThat(decision.evaluation.reasons.map { it.code })
            .containsExactly(PolicyReasonCode.ELIGIBILITY_FAILED)
        assertThat(decision.evaluation.matchedRuleIds).doesNotContain("pr-a")
    }

    @Test
    fun `affordability failure declines with all failed rules`() {
        val decision = PolicyEvaluator.evaluate(application(dsti = "0.60", dti = "12"), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Decline::class.java)
        assertThat(decision.evaluation.reasons.map { it.ruleId }).containsExactly("af-dsti", "af-dti")
    }

    @Test
    fun `missing input fails closed to REFER, never to APPROVE`() {
        val incomplete = application().copy(
            attributes = application().attributes - PolicyAttribute.DSTI,
        )
        val decision = PolicyEvaluator.evaluate(incomplete, bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Refer::class.java)
        assertThat(decision.evaluation.reasons.single().code).isEqualTo(PolicyReasonCode.INPUT_MISSING)
        assertThat(decision.evaluation.reasons.single().ruleId).isEqualTo("af-dsti")
    }

    @Test
    fun `missing policy table fails closed to REFER`() {
        val noExclusion = PolicyBundle(listOf(eligibilityTable(), affordabilityTable(), pricingTable()))
        val decision = PolicyEvaluator.evaluate(application(), noExclusion)

        assertThat(decision).isInstanceOf(PolicyDecision.Refer::class.java)
        assertThat(decision.evaluation.reasons.single().code).isEqualTo(PolicyReasonCode.POLICY_TABLE_MISSING)
        assertThat(decision.evaluation.reasons.single().detail).isEqualTo("EXCLUSION")
    }

    @Test
    fun `not-yet-effective table version is not active and fails closed`() {
        val future = PolicyBundle(
            listOf(
                exclusionTable(effectiveFrom = LocalDate.parse("2027-01-01")),
                eligibilityTable(),
                affordabilityTable(),
                pricingTable(),
            ),
        )
        val decision = PolicyEvaluator.evaluate(application(), future)

        assertThat(decision).isInstanceOf(PolicyDecision.Refer::class.java)
        assertThat(decision.evaluation.reasons.single().code).isEqualTo(PolicyReasonCode.POLICY_TABLE_MISSING)
    }

    @Test
    fun `newer effective version wins within one kind`() {
        val v1 = pricingTable().copy(version = 1, rules = emptyList())
        val v2 = pricingTable().copy(version = 9)
        val bundle = PolicyBundle(listOf(exclusionTable(), eligibilityTable(), affordabilityTable(), v1, v2))
        val decision = PolicyEvaluator.evaluate(application(), bundle)

        assertThat(decision.evaluation.policyVersions[PolicyTableKind.PRICING_BAND]).isEqualTo(9)
    }

    @Test
    fun `unmatched pricing band refers — approval always carries a price`() {
        val decision = PolicyEvaluator.evaluate(application(band = "D"), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Refer::class.java)
        assertThat(decision.evaluation.reasons.single().code).isEqualTo(PolicyReasonCode.PRICING_BAND_UNMATCHED)
    }

    @Test
    fun `type-mismatched rule is REFER not a guessed decision`() {
        val broken = pricingTable().copy(
            rules = listOf(
                PolicyRule(
                    "pr-broken",
                    PolicyAttribute.BUREAU_SCORE_BAND,
                    PolicyOperator.GT,
                    threshold = BigDecimal("1"),
                ),
            ),
        )
        val bundle = PolicyBundle(listOf(exclusionTable(), eligibilityTable(), affordabilityTable(), broken))
        val decision = PolicyEvaluator.evaluate(application(), bundle)

        assertThat(decision).isInstanceOf(PolicyDecision.Refer::class.java)
        assertThat(decision.evaluation.reasons.single().code).isEqualTo(PolicyReasonCode.RULE_NOT_EVALUABLE)
    }

    @Test
    fun `input snapshot hash is deterministic and input-sensitive`() {
        val first = PolicyEvaluator.inputSnapshotHash(application().attributes)
        val same = PolicyEvaluator.inputSnapshotHash(application().attributes)
        val different = PolicyEvaluator.inputSnapshotHash(application(age = "36").attributes)

        assertThat(first).isEqualTo(same)
        assertThat(first).isNotEqualTo(different)
    }

    @Test
    fun `membership operators work on text values`() {
        val decision = PolicyEvaluator.evaluate(application(residency = "FR"), bundle())

        assertThat(decision).isInstanceOf(PolicyDecision.Decline::class.java)
        assertThat(decision.evaluation.reasons.map { it.code }).contains(PolicyReasonCode.ELIGIBILITY_FAILED)
    }
}
