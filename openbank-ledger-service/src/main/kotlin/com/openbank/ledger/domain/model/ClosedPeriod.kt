// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Statutory close granularities (ADR-0096 D1). */
enum class PeriodType {
    MONTH,
    QUARTER,
    YEAR,
    ;

    /** The period of this type containing [date]. */
    fun of(date: LocalDate): AccountingPeriod = when (this) {
        MONTH -> AccountingPeriod(this, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()))
        QUARTER -> {
            val firstMonth = ((date.monthValue - 1) / MONTHS_PER_QUARTER) * MONTHS_PER_QUARTER + 1
            val from = LocalDate.of(date.year, firstMonth, 1)
            val lastMonth = from.plusMonths((MONTHS_PER_QUARTER - 1).toLong())
            AccountingPeriod(this, from, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()))
        }
        YEAR -> AccountingPeriod(this, LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31))
    }

    companion object {
        private const val MONTHS_PER_QUARTER = 3
    }
}

/**
 * A closed-period boundary (ADR-0096 D1). Always a whole month, quarter or calendar year — a
 * statutory close is not an arbitrary window, and allowing one would make two closes able to
 * overlap and disagree about the same journal.
 */
data class AccountingPeriod(val type: PeriodType, val from: LocalDate, val to: LocalDate) {
    init {
        requireValid(!from.isAfter(to)) { "Period from ($from) must not be after to ($to)" }
        requireValid(type.of(from) == this) {
            "$from..$to is not a whole ${type.name.lowercase()} — a statutory period is a whole calendar period"
        }
    }

    /** Stable identity for the period, e.g. `MONTH:2026-07`, `QUARTER:2026-Q3`, `YEAR:2026`. */
    val label: String
        get() = when (type) {
            PeriodType.MONTH -> "MONTH:%04d-%02d".format(from.year, from.monthValue)
            PeriodType.QUARTER -> "QUARTER:%04d-Q%d".format(from.year, (from.monthValue - 1) / 3 + 1)
            PeriodType.YEAR -> "YEAR:%04d".format(from.year)
        }

    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

/**
 * The trial balance for one accounting period — the content a [ClosedPeriodRecord] freezes
 * (ADR-0096 D1).
 *
 * ## Why the canonical form is period-specific rather than shared with [FiscalYearTrialBalance]
 *
 * The obvious move is to extract one canonicalizer and have both use it. That would silently
 * change the fiscal-year canonical JSON — its object starts `{"fiscalYear":…}` — and therefore
 * every already-attested year's [contentHash], turning sealed evidence into apparent tampering.
 * The attestation anchor of a closed period must be stable forever, so the two canonical forms
 * stay distinct on purpose and only the number/string primitives are shared. That is duplication
 * chosen over a hash migration, and the choice is the point.
 */
data class PeriodTrialBalance(val period: AccountingPeriod, val lines: List<TrialBalanceLine>) {
    val totalDebit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalDebit) }
    val totalCredit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalCredit) }

    /** A correct double-entry ledger balances over any period: total debits == total credits. */
    val isBalanced: Boolean get() = totalDebit.compareTo(totalCredit) == 0

    val accountCount: Int get() = lines.map { it.glAccountId }.toSet().size

    /**
     * Deterministic canonical JSON — the input to [contentHash]. Lines sorted by (code, currency),
     * fixed key order, numbers normalized so a scale difference (100 vs 100.00) cannot move the
     * hash. Cosmetic attributes (display name, UUID) are excluded so the hash captures the
     * accounting content, not the presentation.
     */
    fun canonicalJson(): String {
        val sorted = lines.sortedWith(compareBy({ it.code }, { it.currency }))
        return buildString {
            append("{\"period\":\"").append(period.label)
            append("\",\"from\":\"").append(period.from)
            append("\",\"to\":\"").append(period.to)
            append("\",\"totalDebit\":\"").append(canonicalNumber(totalDebit))
            append("\",\"totalCredit\":\"").append(canonicalNumber(totalCredit))
            append("\",\"lines\":[")
            sorted.forEachIndexed { i, line ->
                if (i > 0) append(',')
                append("{\"code\":\"").append(escapeCanonicalJson(line.code))
                append("\",\"type\":\"").append(line.type.name)
                append("\",\"currency\":\"").append(escapeCanonicalJson(line.currency))
                append("\",\"debit\":\"").append(canonicalNumber(line.totalDebit))
                append("\",\"credit\":\"").append(canonicalNumber(line.totalCredit))
                append("\"}")
            }
            append("]}")
        }
    }

    /** SHA-256 of [canonicalJson], lowercase hex — the attestation anchor (zákon 563/1991 průkaznost). */
    fun contentHash(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalJson().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Canonical number form shared by every trial-balance anchor: no trailing zeros, plain string, "0" for zero. */
internal fun canonicalNumber(value: BigDecimal): String {
    val stripped = value.stripTrailingZeros()
    return if (stripped.compareTo(BigDecimal.ZERO) == 0) "0" else stripped.toPlainString()
}

/** Minimal JSON string escaping for canonical rendering. */
internal fun escapeCanonicalJson(value: String): String = buildString {
    value.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}

/**
 * Lifecycle of a statutory period close (ADR-0096 D1).
 *
 * - [DRAFT]  — recomputable. The snapshot can be refreshed from the journal at any time.
 * - [FROZEN] — the period is evidence: the trial balance is immutable and hash-anchored, and no
 *              journal may be dated into the period any more. Late entries route to the next open
 *              period with a link back, rather than rewriting a reported figure.
 */
enum class ClosedPeriodStatus {
    DRAFT,
    FROZEN,
    ;

    val acceptsPostings: Boolean get() = this == DRAFT
}

/**
 * The frozen, attestable artefact for one accounting period (ADR-0096 D1).
 *
 * This is what replaces "the trial balance is a read API". `/api/v1/journals/trial-balance` is a
 * point-in-time query: ask it twice and a posting in between changes the answer, with nothing
 * recording that it did. A [ClosedPeriodRecord] is the same numbers *frozen* — reproducible,
 * re-verifiable and immutable — which is the difference between a figure and evidence
 * (zákon 563/1991 Sb. průkaznost/úplnost).
 *
 * Four-eyes: [drafted]By and [frozenBy] must differ, mirroring the fiscal-year attestation control
 * (#869). A draft with no recorded author can never be frozen — without a maker there is nothing
 * to separate the checker from.
 */
data class ClosedPeriodRecord(
    val id: UUID,
    val period: AccountingPeriod,
    val status: ClosedPeriodStatus,
    val computedAt: Instant,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val accountCount: Int,
    val contentHash: String,
    val draftedBy: String? = null,
    val frozenBy: String? = null,
    val frozenAt: Instant? = null,
) {
    /**
     * DRAFT → FROZEN. The caller must have re-verified [contentHash] against a fresh computation
     * first; this fail-closed check is the last line so a future caller cannot bypass four-eyes by
     * going straight at the aggregate.
     */
    fun freeze(frozenBy: String, frozenAt: Instant): ClosedPeriodRecord {
        checkConflict(status == ClosedPeriodStatus.DRAFT) {
            "Period ${period.label} is not DRAFT (status=$status) — a frozen period is immutable"
        }
        checkConflict(draftedBy != null) {
            "Period ${period.label} draft has no recorded author — cannot freeze (four-eyes)"
        }
        checkConflict(draftedBy != frozenBy) {
            "Four-eyes violation: ${period.label} must be frozen by someone other than the draft author"
        }
        return copy(status = ClosedPeriodStatus.FROZEN, frozenBy = frozenBy, frozenAt = frozenAt)
    }

    companion object {
        fun draftOf(
            trialBalance: PeriodTrialBalance,
            computedAt: Instant,
            id: UUID = UUID.randomUUID(),
            draftedBy: String? = null,
        ) = ClosedPeriodRecord(
            id = id,
            period = trialBalance.period,
            status = ClosedPeriodStatus.DRAFT,
            computedAt = computedAt,
            totalDebits = trialBalance.totalDebit,
            totalCredits = trialBalance.totalCredit,
            accountCount = trialBalance.accountCount,
            contentHash = trialBalance.contentHash(),
            draftedBy = draftedBy,
        )
    }
}

/**
 * The result of re-verifying a [ClosedPeriodRecord] against a fresh computation. Read-only: it
 * never flips state. For a FROZEN period `matches == false` means a reported figure moved after it
 * was frozen — which the period lock prevents, but a verify still *proves*, and proof is the point
 * of an attestation.
 */
data class ClosedPeriodVerification(
    val period: AccountingPeriod,
    val status: ClosedPeriodStatus,
    val recordedHash: String,
    val recomputedHash: String,
    val matches: Boolean,
    val balanced: Boolean,
    val recomputedAt: Instant,
)
