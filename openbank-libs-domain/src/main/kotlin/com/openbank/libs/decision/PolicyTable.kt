// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.decision

import java.math.BigDecimal
import java.time.LocalDate

/** Table kinds evaluated in this fixed order (ADR-0213 D2). */
enum class PolicyTableKind { EXCLUSION, ELIGIBILITY, AFFORDABILITY, PRICING_BAND }

/**
 * One versioned, effective-dated rule inside a [PolicyTable]. Numeric operators use
 * [threshold]; text operators use [values]. In a PRICING_BAND table a matching rule
 * must carry [band] — the assigned price band, never a decline/approve flip.
 */
data class PolicyRule(
    val id: String,
    val attribute: PolicyAttribute,
    val operator: PolicyOperator,
    val threshold: BigDecimal? = null,
    val values: Set<String> = emptySet(),
    val band: String? = null,
    val detail: String = "",
)

/**
 * A versioned, effective-dated decision table. Activation is four-eyes and recorded
 * (ADR-0213 D4); the service pins [version] on every evaluation it persists.
 */
data class PolicyTable(
    val kind: PolicyTableKind,
    val name: String,
    val version: Int,
    val effectiveFrom: LocalDate,
    val rules: List<PolicyRule>,
    val effectiveTo: LocalDate? = null,
) {
    fun isActive(asOf: LocalDate): Boolean =
        !asOf.isBefore(effectiveFrom) && (effectiveTo == null || asOf.isBefore(effectiveTo))
}

/** The full policy set evaluated as one unit; every table kind must resolve to an active table. */
data class PolicyBundle(val tables: List<PolicyTable>) {
    fun active(kind: PolicyTableKind, asOf: LocalDate): PolicyTable? =
        tables.filter { it.kind == kind && it.isActive(asOf) }.maxByOrNull { it.version }
}
