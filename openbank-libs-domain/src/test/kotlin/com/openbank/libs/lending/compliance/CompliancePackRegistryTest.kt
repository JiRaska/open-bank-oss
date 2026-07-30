// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import com.openbank.libs.lending.origination.OriginationState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Covers ADR-0212 D3/D4: four-eyes activation, immutable versions, effective-dated lookup. */
class CompliancePackRegistryTest {

    private val now: Instant = Instant.parse("2026-07-29T12:00:00Z")

    private fun pack(version: Int, effectiveFrom: String = "2026-08-01"): CompiledCompliancePack =
        CompliancePackCompiler.compile(
            CompliancePack(
                jurisdiction = "CZ",
                productType = PackProductType.CONSUMER_CREDIT,
                version = version,
                effectiveFrom = LocalDate.parse(effectiveFrom),
                coolingOffDays = 14,
                aprDisclosure = AprDisclosure("RPSN", "cs-CZ"),
                terminationRules = TerminationRules(
                    noticePeriodDays = 30,
                    permittedGrounds = setOf(TerminationGround.DEFAULT_DPD),
                ),
            ),
        )

    private fun approvedProposal(
        compiled: CompiledCompliancePack,
        maker: String = "compliance-officer-1",
        checker: String = "compliance-officer-2",
    ): Proposal<CompiledCompliancePack> = Proposal(
        id = "prop-${compiled.pack.version}",
        action = compiled,
        proposedBy = maker,
        proposedAt = now,
    ).approve(checker, now.plusSeconds(60), "reviewed against legal opinion")

    @Test
    fun `approved four-eyes proposal activates the pack`() {
        val registry = CompliancePackRegistry()
        registry.activate(approvedProposal(pack(1)))

        val active = registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-08-15"))
        assertThat(active).isNotNull
        assertThat(active!!.pack.version).isEqualTo(1)
    }

    @Test
    fun `pending proposal cannot activate`() {
        val registry = CompliancePackRegistry()
        val pending = Proposal(id = "p1", action = pack(1), proposedBy = "maker-1", proposedAt = now)

        assertThatThrownBy { registry.activate(pending) }
            .isInstanceOf(PackActivationException::class.java)
            .hasMessageContaining("APPROVED")
    }

    @Test
    fun `maker-checker state machine refuses self-approval before the registry is consulted`() {
        val pending = Proposal(id = "p2", action = pack(1), proposedBy = "same-person", proposedAt = now)

        assertThatThrownBy { pending.approve("same-person", now) }
            .isInstanceOf(MakerCheckerViolation::class.java)
    }

    @Test
    fun `re-activating the same version is refused — activated versions are immutable history`() {
        val registry = CompliancePackRegistry()
        registry.activate(approvedProposal(pack(1)))

        assertThatThrownBy { registry.activate(approvedProposal(pack(1))) }
            .isInstanceOf(PackActivationException::class.java)
            .hasMessageContaining("already activated")
    }

    @Test
    fun `lookup resolves the version effective at the date — in-flight pins are stable`() {
        val registry = CompliancePackRegistry()
        registry.activate(approvedProposal(pack(1, effectiveFrom = "2026-08-01")))
        registry.activate(approvedProposal(pack(2, effectiveFrom = "2026-09-01")))

        val before = registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-08-15"))
        val after = registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-09-15"))
        val tooEarly = registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-07-15"))

        assertThat(before!!.pack.version).isEqualTo(1)
        assertThat(after!!.pack.version).isEqualTo(2)
        assertThat(tooEarly).isNull()
    }

    @Test
    fun `no active pack returns null — the service fails closed`() {
        val registry = CompliancePackRegistry()

        assertThat(registry.activePack("DE", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-08-15")))
            .isNull()
        assertThat(registry.allActive(LocalDate.parse("2026-08-15"))).isEmpty()
    }

    @Test
    fun `evaluator exposes mandatory steps, disclosures and mandatory checks from the pinned pack`() {
        val compiled = CompliancePackCompiler.compile(
            CompliancePackParser.fromJson(
                """
                {
                  "jurisdiction": "CZ",
                  "productType": "CONSUMER_CREDIT",
                  "version": 1,
                  "effectiveFrom": "2026-08-01",
                  "coolingOffDays": 14,
                  "aprDisclosure": { "label": "RPSN", "locale": "cs-CZ" },
                  "requiredSteps": ["DOCS_REQUIRED"],
                  "terminationRules": { "noticePeriodDays": 30, "permittedGrounds": ["DEFAULT_DPD"] },
                  "disclosures": [
                    { "id": "secci", "templateKey": "cz/secci-v1", "languages": ["cs"], "stage": "PRE_CONTRACTUAL" },
                    { "id": "contract", "templateKey": "cz/contract-v1", "languages": ["cs"], "stage": "CONTRACTUAL" }
                  ],
                  "mandatoryChecks": [
                    { "id": "cz-dsti-cap", "attribute": "DSTI", "operator": "LTE", "threshold": 0.45 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertThat(CompliancePackEvaluator.mandatorySteps(compiled))
            .containsExactly(OriginationState.DOCS_REQUIRED)
        assertThat(CompliancePackEvaluator.disclosuresFor(compiled, DisclosureStage.PRE_CONTRACTUAL).map { it.id })
            .containsExactly("secci")
        assertThat(CompliancePackEvaluator.mandatoryEligibilityRules(compiled).map { it.id })
            .containsExactly("cz-dsti-cap")
        assertThat(CompliancePackEvaluator.coolingOffDays(compiled)).isEqualTo(14)
        assertThat(CompliancePackEvaluator.terminationRules(compiled).defaultDpdThreshold).isEqualTo(90)
    }
}
