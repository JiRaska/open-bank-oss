// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Per-account-type subtotal of a fiscal-year trial balance (rozvaha/výsledovka grouping:
 * ASSET/LIABILITY/EQUITY on the balance-sheet side, INCOME/EXPENSE on the P&L side).
 */
data class TrialBalanceSection(val type: GlAccountType, val lines: List<TrialBalanceLine>) {
    val totalDebit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalDebit) }
    val totalCredit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalCredit) }
    val net: BigDecimal get() = totalDebit.subtract(totalCredit)
}

/**
 * The entity-level GL trial balance for one fiscal year (ADR-0078 D5 / issue #471, increment 1).
 *
 * Aggregates POSTED journal activity whose entryDate falls WITHIN the fiscal year, per GL
 * account, grouped by account type. The double-entry invariant must hold over the whole GL for
 * any entry-date range: sum(debits) == sum(credits), else the journal itself is corrupt.
 *
 * [canonicalJson] is the attestation anchor: a deterministic rendering (lines sorted by
 * (code, currency), fixed key order, normalized number form) whose SHA-256 [contentHash] is
 * frozen on the [YearCloseRecord] and re-verified fail-closed at attestation time. Cosmetic
 * attributes (account display name, UUID) are deliberately excluded so the hash captures the
 * accounting content — code, classification, currency and the debit/credit totals.
 */
data class FiscalYearTrialBalance(val fiscalYear: Int, val lines: List<TrialBalanceLine>) {
    val totalDebit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalDebit) }
    val totalCredit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalCredit) }

    /** A correct double-entry ledger balances over any period: total debits == total credits. */
    val isBalanced: Boolean get() = totalDebit.compareTo(totalCredit) == 0

    /** Distinct GL accounts with activity in the fiscal year. */
    val accountCount: Int get() = lines.map { it.glAccountId }.toSet().size

    /** Lines grouped by account type, in declaration order of [GlAccountType]; empty types omitted. */
    val sections: List<TrialBalanceSection>
        get() = GlAccountType.entries
            .map { type -> TrialBalanceSection(type, lines.filter { it.type == type }) }
            .filter { it.lines.isNotEmpty() }

    /**
     * Deterministic canonical JSON of this trial balance — the input to [contentHash].
     * Stability contract: lines sorted by (code, currency), fixed key order, numbers rendered
     * via [canonicalNumber] so scale differences (100 vs 100.00) cannot change the hash.
     */
    fun canonicalJson(): String {
        val sorted = lines.sortedWith(compareBy({ it.code }, { it.currency }))
        return buildString {
            append("{\"fiscalYear\":").append(fiscalYear)
            append(",\"totalDebit\":\"").append(canonicalNumber(totalDebit))
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

enum class YearCloseStatus { DRAFT, ATTESTED }

/**
 * The result of re-verifying a [YearCloseRecord]'s anchor against a fresh trial-balance computation
 * (issue #869). A read-only integrity check: it never flips state. [matches] is the headline — for an
 * ATTESTED year a `false` means the sealed period was tampered with (postings landed after attest,
 * which the period lock now prevents but a verify still proves); for a DRAFT it means the draft is
 * stale and should be refreshed before attesting.
 */
data class YearCloseVerification(
    val fiscalYear: Int,
    val status: YearCloseStatus,
    val recordedHash: String,
    val recomputedHash: String,
    val matches: Boolean,
    val balanced: Boolean,
    val recomputedAt: Instant,
)

/**
 * The persisted year-close artefact for one fiscal year (ADR-0078 D5, increment 1): the frozen
 * totals + content hash of the trial balance the close was computed from, and the attestation
 * trail. Lifecycle: DRAFT (recomputable/refreshable) → ATTESTED (immutable, hash re-verified
 * fail-closed against a fresh computation at the moment of attestation).
 */
data class YearCloseRecord(
    val id: UUID,
    val fiscalYear: Int,
    val status: YearCloseStatus,
    val computedAt: Instant,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val accountCount: Int,
    val contentHash: String,
    val draftedBy: String? = null,
    val attestedBy: String? = null,
    val attestedAt: Instant? = null,
) {
    init {
        requireValid(fiscalYear in MIN_FISCAL_YEAR..MAX_FISCAL_YEAR) { "fiscalYear out of range: $fiscalYear" }
    }

    /**
     * DRAFT → ATTESTED. The caller must have re-verified [contentHash] against a fresh computation.
     *
     * Defense-in-depth four-eyes (#869, maker != checker): the attestor MUST differ from
     * [draftedBy], and a null [draftedBy] (a draft predating four-eyes tracking) can never be
     * attested. The application layer surfaces these as 409s; this fail-closed [check] is the last
     * line so a future caller cannot bypass the control by going straight at the aggregate.
     */
    fun attest(attestedBy: String, attestedAt: Instant): YearCloseRecord {
        checkConflict(status == YearCloseStatus.DRAFT) { "Year close $fiscalYear is not DRAFT (status=$status)" }
        checkConflict(draftedBy != null) {
            "Year close $fiscalYear draft has no recorded author — cannot attest (four-eyes)"
        }
        checkConflict(draftedBy != attestedBy) {
            "Four-eyes violation: attestor $attestedBy must differ from the draft author for year $fiscalYear"
        }
        return copy(status = YearCloseStatus.ATTESTED, attestedBy = attestedBy, attestedAt = attestedAt)
    }

    companion object {
        const val MIN_FISCAL_YEAR = 2000
        const val MAX_FISCAL_YEAR = 2999

        /**
         * A fresh DRAFT snapshotting the given trial balance. [draftedBy] is the maker (the actor
         * who produced this snapshot); on a refresh it is the actor who re-computed it, so the
         * attestor (checker) always reviews against a known author (four-eyes, #869).
         */
        fun draftOf(
            trialBalance: FiscalYearTrialBalance,
            computedAt: Instant,
            id: UUID = UUID.randomUUID(),
            draftedBy: String? = null,
        ) = YearCloseRecord(
            id = id,
            fiscalYear = trialBalance.fiscalYear,
            status = YearCloseStatus.DRAFT,
            computedAt = computedAt,
            totalDebits = trialBalance.totalDebit,
            totalCredits = trialBalance.totalCredit,
            accountCount = trialBalance.accountCount,
            contentHash = trialBalance.contentHash(),
            draftedBy = draftedBy,
        )
    }
}
