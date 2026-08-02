// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Domain validation failure (422). */
class TaxValidationException(message: String) : RuntimeException(message)

/** Domain conflict / invariant violation (409). */
class TaxConflictException(message: String) : RuntimeException(message)

inline fun requireValid(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw TaxValidationException(lazyMessage())
}

inline fun checkConflict(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw TaxConflictException(lazyMessage())
}

/**
 * The §38d filing period: one calendar month of withheld tax.
 *
 * Monthly because that is the cadence `WithholdingRemittancePolicy` already assembles the cash leg
 * on (odvod due the last day of the month following the withholding month, ADR-0038). Deriving the
 * filing period from anything else would let the return and the payment describe different months.
 */
data class FilingPeriod(val year: Int, val month: Int) : Comparable<FilingPeriod> {
    init {
        requireValid(year in MIN_YEAR..MAX_YEAR) { "year out of range: $year" }
        requireValid(month in 1..MONTHS_PER_YEAR) { "month out of range: $month" }
    }

    /** `2026-07` — stable, sortable, and what the operator sees on the filing. */
    val label: String get() = "%04d-%02d".format(year, month)

    val firstDay: LocalDate get() = LocalDate.of(year, month, 1)
    val lastDay: LocalDate get() = YearMonth.of(year, month).atEndOfMonth()

    /**
     * §38d odst. 3 statutory deadline: the last day of the month following the withholding month —
     * the same deadline the remittance policy uses for the payment, so the return and the cash leg
     * cannot drift apart.
     */
    val dueDate: LocalDate get() = YearMonth.of(year, month).plusMonths(1).atEndOfMonth()

    override fun compareTo(other: FilingPeriod): Int = compareValuesBy(this, other, { it.year }, { it.month })

    companion object {
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2999
        private const val MONTHS_PER_YEAR = 12

        fun of(date: LocalDate) = FilingPeriod(date.year, date.monthValue)
    }
}

/**
 * One remittance batch this service has observed, recorded verbatim from
 * `interest.withholding.remitted.v1`.
 *
 * Stored rather than aggregated on the fly for two reasons: the filing must be reproducible from
 * what was actually received (an auditor asks "which batches make up this number"), and storing
 * them keyed by [remittanceId] makes re-delivery a no-op. Kafka is at-least-once, so a redelivered
 * batch would otherwise be counted twice and the return would overstate the tax withheld.
 */
data class ObservedRemittance(
    val remittanceId: UUID,
    val period: FilingPeriod,
    val currency: String,
    val totalTaxAmount: BigDecimal,
    val itemCount: Int,
    val dueDate: LocalDate,
    val observedAt: Instant,
) {
    init {
        requireValid(totalTaxAmount.signum() >= 0) { "totalTaxAmount must not be negative: $totalTaxAmount" }
        requireValid(itemCount >= 0) { "itemCount must not be negative: $itemCount" }
        requireValid(currency.length == CURRENCY_CODE_LENGTH) { "currency must be an ISO-4217 code: $currency" }
    }

    companion object {
        private const val CURRENCY_CODE_LENGTH = 3
    }
}

/**
 * Where a §38d filing is in its lifecycle (ADR-0180).
 *
 * - [OPEN]      — the period is still accruing observed remittances.
 * - [ASSEMBLED] — the period is closed and its totals are fixed. Re-assembly is REFUSED, not
 *                 idempotent: if the totals have moved, a remittance arrived late, and that must
 *                 reach an operator rather than be re-totalled underneath a return they may
 *                 already have submitted. The correction path is a dodatečné vyúčtování, not a
 *                 quiet recompute.
 * - [FILED]     — the operator has submitted the return through the EPO portal or datová schránka
 *                 and recorded the reference. This service is the system of record for *what was
 *                 filed*, not a transport.
 *
 * There is no automatic submission state: the finanční úřad exposes no public real-time filing API,
 * so v1 renders and exports an artifact and a human submits it (ADR-0180 §Transport).
 */
enum class FilingStatus {
    OPEN,
    ASSEMBLED,
    FILED,
    ;

    val acceptsRemittances: Boolean get() = this == OPEN
}

/**
 * The §38d return for one month — this platform's system of record for the filing (ADR-0180).
 *
 * ADR-0038 closed the *money movement* (interest-service withholds and remits the cash) and
 * explicitly delegated the *statutory filing* to "the downstream payment/reporting consumer"
 * without assigning an owner. This aggregate is that owner. Booking the cash without filing the
 * return is itself a compliance gap: the FÚ needs the periodic statement, not just money arriving.
 */
data class TaxFilingRecord(
    val id: UUID,
    val period: FilingPeriod,
    val status: FilingStatus,
    val currency: String,
    val totalTaxAmount: BigDecimal,
    val remittanceCount: Int,
    val itemCount: Int,
    val assembledAt: Instant? = null,
    val assembledBy: String? = null,
    val filedAt: Instant? = null,
    val filedBy: String? = null,
    val filingReference: String? = null,
    val version: Long = 0L,
) {
    val dueDate: LocalDate get() = period.dueDate

    /** True once the statutory deadline has passed without the return being [FilingStatus.FILED]. */
    fun isOverdueAt(date: LocalDate): Boolean = status != FilingStatus.FILED && date.isAfter(dueDate)

    /**
     * OPEN → ASSEMBLED, freezing the totals computed from the observed remittances.
     *
     * Deliberately NOT idempotent across a re-assembly with different totals: if the numbers have
     * moved since the period was assembled, that means a remittance arrived late, which is exactly
     * the situation an operator must be told about rather than have quietly re-totalled underneath
     * a return they may already have filed.
     */
    fun assemble(
        totalTaxAmount: BigDecimal,
        remittanceCount: Int,
        itemCount: Int,
        by: String,
        at: Instant,
    ): TaxFilingRecord {
        checkConflict(status == FilingStatus.OPEN) { "Filing ${period.label} is not OPEN (status=$status)" }
        requireValid(by.isNotBlank()) { "Assembly requires an actor" }
        return copy(
            status = FilingStatus.ASSEMBLED,
            totalTaxAmount = totalTaxAmount,
            remittanceCount = remittanceCount,
            itemCount = itemCount,
            assembledAt = at,
            assembledBy = by,
            version = version + 1,
        )
    }

    /**
     * ASSEMBLED → FILED. [reference] is the FÚ/EPO submission reference — required, because a
     * filing recorded without one cannot be evidenced later, which defeats the point of recording it.
     *
     * Four-eyes (maker != checker): the actor who filed must differ from the one who assembled, and
     * an assembly with no recorded author can never be filed. Same control as the ledger's
     * fiscal-year attestation — without a maker there is nothing to separate the checker from.
     */
    fun markFiled(reference: String, by: String, at: Instant): TaxFilingRecord {
        checkConflict(status == FilingStatus.ASSEMBLED) {
            "Filing ${period.label} is not ASSEMBLED (status=$status) — assemble it before filing"
        }
        requireValid(reference.isNotBlank()) { "A filing reference is required to record a submission" }
        requireValid(by.isNotBlank()) { "Filing requires an actor" }
        checkConflict(assembledBy != null) {
            "Filing ${period.label} has no recorded assembler — cannot file (four-eyes)"
        }
        checkConflict(assembledBy != by) {
            "Four-eyes violation: $by assembled ${period.label} and may not also file it"
        }
        return copy(
            status = FilingStatus.FILED,
            filedAt = at,
            filedBy = by,
            filingReference = reference,
            version = version + 1,
        )
    }

    companion object {
        /** A fresh OPEN filing for [period], with nothing observed yet. */
        fun open(period: FilingPeriod, currency: String, id: UUID = Ids.newId()) = TaxFilingRecord(
            id = id,
            period = period,
            status = FilingStatus.OPEN,
            currency = currency,
            totalTaxAmount = BigDecimal.ZERO,
            remittanceCount = 0,
            itemCount = 0,
        )
    }
}
