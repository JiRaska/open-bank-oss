// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.corporate

import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.requireValid
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * How a fact is read for a reporting period.
 *
 * - [STOCK]: a standing figure (share capital, headcount). The value effective at the period end is
 *   the one in force on that date, however long ago it was entered.
 * - [PERIOD]: a figure that belongs to one period (a year's dividend). Only an entry effective
 *   INSIDE the reported period counts — last year's dividend is never carried into this year's
 *   return as if it were this year's.
 */
enum class FactKind { STOCK, PERIOD }

/**
 * The corporate facts no system in this platform produces (ADR-0336, #12425): capital structure,
 * organisation, governing bodies and dividends of the reporting company. [integral] facts are
 * counts and must be whole numbers.
 */
enum class CorporateFact(val kind: FactKind, val integral: Boolean) {
    SHARE_CAPITAL(FactKind.STOCK, integral = false),
    REGULATORY_CAPITAL(FactKind.STOCK, integral = false),
    CAPITAL_REQUIREMENT(FactKind.STOCK, integral = false),
    EMPLOYEES_COUNT(FactKind.STOCK, integral = true),
    QUALIFYING_SHAREHOLDERS_COUNT(FactKind.STOCK, integral = true),
    BOARD_OF_DIRECTORS_MEMBERS(FactKind.STOCK, integral = true),
    SUPERVISORY_BOARD_MEMBERS(FactKind.STOCK, integral = true),
    DIVIDEND_PAID_OR_PLANNED(FactKind.PERIOD, integral = false),
}

enum class RegisterEntryStatus { PROPOSED, APPROVED, REJECTED }

/**
 * One versioned, effective-dated value of one [CorporateFact] for one reporting entity.
 *
 * Rows are append-only: a change is a NEW entry (a later [effectiveFrom], or the next [version]
 * for the same date), and a decision only moves a PROPOSED entry to APPROVED or REJECTED — by a
 * principal other than its proposer. [evidence] names the document the figure comes from
 * (general-meeting minutes, the capital calculation, the HR headcount report), so an auditor can
 * trace every filed number to its source.
 */
data class CorporateRegisterEntry(
    val id: UUID,
    val entityId: String,
    val fact: CorporateFact,
    val value: BigDecimal,
    val effectiveFrom: LocalDate,
    val version: Int,
    val reason: String,
    val evidence: String,
    val proposedBy: String,
    val proposedAt: Instant,
    val status: RegisterEntryStatus = RegisterEntryStatus.PROPOSED,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
) {
    init {
        requireValid(entityId.isNotBlank()) { "entityId must not be blank" }
        requireValid(value.signum() >= 0) { "$fact must not be negative" }
        requireValid(!fact.integral || value.stripTrailingZeros().scale() <= 0) {
            "$fact is a count: whole numbers only"
        }
        requireValid(version >= 1) { "version starts at 1" }
        requireValid(reason.isNotBlank()) { "reason must not be blank" }
        requireValid(evidence.isNotBlank()) { "evidence must name the source document" }
    }

    fun approve(by: String, at: Instant): CorporateRegisterEntry = decide(RegisterEntryStatus.APPROVED, by, at)

    fun reject(by: String, at: Instant): CorporateRegisterEntry = decide(RegisterEntryStatus.REJECTED, by, at)

    private fun decide(to: RegisterEntryStatus, by: String, at: Instant): CorporateRegisterEntry {
        if (status != RegisterEntryStatus.PROPOSED) throw TaxConflictException("register entry $id is $status")
        if (by == proposedBy) {
            throw TaxConflictException("Four-eyes violation: $by proposed register entry $id and may not decide it")
        }
        return copy(status = to, decidedBy = by, decidedAt = at)
    }
}

/**
 * The value of [fact] that a return for the period [periodStart]..[periodEnd] reads, or null when
 * none is approved: the APPROVED entry with the latest effective date (inside the period for a
 * [FactKind.PERIOD] fact, on or before its end for a [FactKind.STOCK] one), highest version first.
 */
fun effectiveEntry(
    entries: List<CorporateRegisterEntry>,
    fact: CorporateFact,
    periodStart: LocalDate,
    periodEnd: LocalDate,
): CorporateRegisterEntry? = entries
    .filter { it.fact == fact && it.status == RegisterEntryStatus.APPROVED && !it.effectiveFrom.isAfter(periodEnd) }
    .filter { fact.kind == FactKind.STOCK || !it.effectiveFrom.isBefore(periodStart) }
    .maxWithOrNull(compareBy<CorporateRegisterEntry>({ it.effectiveFrom }, { it.version }))
