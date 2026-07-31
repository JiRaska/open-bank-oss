// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.libs.decision.PolicyAttribute
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.decision.PolicyOperator
import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.decision.PolicyTable
import com.openbank.libs.decision.PolicyTableKind
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import java.math.BigDecimal
import java.time.LocalDate

/** The active, versioned credit policy set for the ADR-0213 deterministic evaluation. */
interface CreditPolicyPort {
    fun activeBundle(asOf: LocalDate): Uni<PolicyBundle>
}

/**
 * Phase-1 deterministic starter bundle (ADR-0213 D3): a conservative, code-seeded
 * policy — eligibility (majority, residency), affordability (DSTI ≤ 0.45, DTI ≤ 8),
 * DSTI-banded pricing. The pinned pack's `mandatoryChecks` are merged into the
 * ELIGIBILITY table at evaluation time, so the statutory floor rides the same
 * fail-closed engine. The four-eyes-governed table store (ADR-0213 D4) replaces this
 * default without touching the wiring.
 */
@ApplicationScoped
@Default
class StarterCreditPolicy : CreditPolicyPort {

    override fun activeBundle(asOf: LocalDate): Uni<PolicyBundle> = Uni.createFrom().item(
        PolicyBundle(
            tables = listOf(
                exclusionTable(),
                eligibilityTable(),
                affordabilityTable(),
                pricingTable(),
            ),
        ),
    )

    private fun exclusionTable() = PolicyTable(
        kind = PolicyTableKind.EXCLUSION,
        name = "starter-exclusion",
        version = 1,
        effectiveFrom = LocalDate.EPOCH,
        rules = listOf(
            PolicyRule(
                id = "starter-ex-adverse",
                attribute = PolicyAttribute.CUSTOMER_TYPE,
                operator = PolicyOperator.EQ,
                values = setOf("ADVERSE_BUREAU"),
                detail = "adverse bureau data",
            ),
        ),
    )

    private fun eligibilityTable() = PolicyTable(
        kind = PolicyTableKind.ELIGIBILITY,
        name = "starter-eligibility",
        version = 1,
        effectiveFrom = LocalDate.EPOCH,
        rules = listOf(
            PolicyRule(
                id = "starter-el-age",
                attribute = PolicyAttribute.AGE_YEARS,
                operator = PolicyOperator.GTE,
                threshold = BigDecimal("18"),
                detail = "majority required",
            ),
            PolicyRule(
                id = "starter-el-residency",
                attribute = PolicyAttribute.RESIDENCY,
                operator = PolicyOperator.IN,
                values = setOf("CZ", "DE", "SK"),
                detail = "supported residency",
            ),
        ),
    )

    private fun affordabilityTable() = PolicyTable(
        kind = PolicyTableKind.AFFORDABILITY,
        name = "starter-affordability",
        version = 1,
        effectiveFrom = LocalDate.EPOCH,
        rules = listOf(
            PolicyRule(
                id = "starter-af-dsti",
                attribute = PolicyAttribute.DSTI,
                operator = PolicyOperator.LTE,
                threshold = BigDecimal("0.45"),
                detail = "debt service to income above 45%",
            ),
            PolicyRule(
                id = "starter-af-dti",
                attribute = PolicyAttribute.DTI,
                operator = PolicyOperator.LTE,
                threshold = BigDecimal("8"),
                detail = "debt to income above 8",
            ),
        ),
    )

    private fun pricingTable() = PolicyTable(
        kind = PolicyTableKind.PRICING_BAND,
        name = "starter-pricing",
        version = 1,
        effectiveFrom = LocalDate.EPOCH,
        rules = listOf(
            PolicyRule(
                id = "starter-pr-prime",
                attribute = PolicyAttribute.DSTI,
                operator = PolicyOperator.LTE,
                threshold = BigDecimal("0.25"),
                band = "PRIME",
            ),
            PolicyRule(
                id = "starter-pr-standard",
                attribute = PolicyAttribute.DSTI,
                operator = PolicyOperator.LTE,
                threshold = BigDecimal("0.45"),
                band = "STANDARD",
            ),
        ),
    )
}
