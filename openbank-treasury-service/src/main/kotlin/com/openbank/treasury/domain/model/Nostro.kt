// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Direction as the CORRESPONDENT reports it: CRDT is money into our nostro, DBIT money out. */
enum class StatementDirection { CRDT, DBIT }

/** One `Ntry` of a camt.053 statement, as the correspondent booked it. */
data class StatementEntry(
    val sequence: Int,
    val amount: BigDecimal,
    val currency: String,
    val direction: StatementDirection,
    val bookingDate: LocalDate,
    /** The first present of EndToEndId / AcctSvcrRef / unstructured remittance, else null. */
    val reference: String?,
) {
    init {
        require(amount.signum() > 0) { "statement entry amount must be positive" }
    }
}

/**
 * A correspondent's end-of-day statement for one nostro account (camt.053 `Stmt`). Balances are
 * signed from OUR side: positive means the correspondent holds money for us (CRDT balance).
 */
data class NostroStatement(
    val statementId: String,
    val iban: String,
    val currency: String,
    val statementDate: LocalDate,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val entries: List<StatementEntry>,
) {
    init {
        require(entries.all { it.currency == currency }) { "every entry must be in the statement currency $currency" }
        val movement = entries.sumOf { if (it.direction == StatementDirection.CRDT) it.amount else it.amount.negate() }
        require(openingBalance.add(movement).compareTo(closingBalance) == 0) {
            "statement $statementId does not foot: opening $openingBalance + entries $movement != closing $closingBalance"
        }
        require(ChronoUnit.DAYS.between(firstDate, lastDate) < MAX_SPAN_DAYS) {
            "statement $statementId spans $firstDate..$lastDate, more than $MAX_SPAN_DAYS days"
        }
    }

    /** Earliest of the entry booking dates and the statement (closing balance) date. */
    val firstDate: LocalDate get() = (entries.map { it.bookingDate } + statementDate).min()

    /** Latest of the entry booking dates and the statement (closing balance) date. */
    val lastDate: LocalDate get() = (entries.map { it.bookingDate } + statementDate).max()

    companion object {
        /** A multi-day statement is read from the ledger over its whole span; bounded so that read is too. */
        const val MAX_SPAN_DAYS = 31L
    }
}

/** One ledger journal line on the nostro GL, as read from ledger-service. */
data class LedgerNostroLine(
    val journalId: UUID,
    val lineId: UUID,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val side: Side,
    val amount: BigDecimal,
    val currency: String,
    val description: String?,
)

enum class MatchType {
    /** Amount, direction, date AND reference agree. */
    EXACT,

    /** Amount, direction and date agree and exactly one candidate remained; no reference agreed. */
    AMOUNT_DATE,
}

data class NostroMatch(val entry: StatementEntry, val line: LedgerNostroLine, val type: MatchType)

/**
 * The ledger balances are NULL when the ledger cannot state them in the statement currency — its
 * balance reads aggregate `base_amount` (CZK) only, so a EUR nostro has no comparable figure, and
 * [balanceNotStated] says why. A CZK figure against a EUR statement would be a difference nobody
 * could stand behind (ADR-0097), so none is shown.
 */
data class NostroReconciliation(
    val statement: NostroStatement,
    val glCode: String,
    val ledgerOpeningBalance: BigDecimal?,
    val ledgerClosingBalance: BigDecimal?,
    val matches: List<NostroMatch>,
    val unmatchedStatementEntries: List<StatementEntry>,
    val unmatchedLedgerLines: List<LedgerNostroLine>,
    val balanceNotStated: String? = null,
) {
    init {
        require((ledgerOpeningBalance == null) == (ledgerClosingBalance == null)) {
            "ledger opening and closing balances are stated together or not at all"
        }
        require((ledgerOpeningBalance == null) == (balanceNotStated != null)) {
            "a missing ledger balance must carry its reason, and only a missing one"
        }
    }

    val openingDifference: BigDecimal? get() = ledgerOpeningBalance?.let { statement.openingBalance.subtract(it) }
    val closingDifference: BigDecimal? get() = ledgerClosingBalance?.let { statement.closingBalance.subtract(it) }

    /**
     * true only when both sides are fully matched AND the balances agree; false on any unmatched
     * item or balance difference; NULL when every item matched but the balances could not be
     * compared — undetermined, not a pass and not a break. Nothing is ever posted.
     */
    val reconciled: Boolean?
        get() {
            if (unmatchedStatementEntries.isNotEmpty() || unmatchedLedgerLines.isNotEmpty()) return false
            val opening = openingDifference ?: return null
            val closing = closingDifference ?: return null
            return opening.signum() == 0 && closing.signum() == 0
        }
}

/**
 * Pure matcher. Nostro is an ASSET, so a correspondent CRDT (money in) is our DEBIT and a DBIT is
 * our CREDIT. Pass 1 matches on amount + direction + date + reference (the reference equals the
 * ledger transaction id, or equals a whole token of the journal description). Pass 2 pairs what is left on
 * amount + direction + date only when exactly ONE candidate exists on each side — an ambiguous
 * pair stays unmatched for a person to resolve. It never proposes or posts an adjusting entry.
 */
object NostroMatcher {

    fun match(
        statement: NostroStatement,
        glCode: String,
        ledgerLines: List<LedgerNostroLine>,
        ledgerOpeningBalance: BigDecimal?,
        ledgerClosingBalance: BigDecimal?,
        balanceNotStated: String? = null,
    ): NostroReconciliation {
        val openEntries = statement.entries.toMutableList()
        val openLines = ledgerLines.toMutableList()
        val matches = mutableListOf<NostroMatch>()

        for (entry in statement.entries) {
            val line = openLines.firstOrNull { sameMovement(entry, it) && referenceAgrees(entry, it) } ?: continue
            matches += NostroMatch(entry, line, MatchType.EXACT)
            openEntries.remove(entry)
            openLines.remove(line)
        }
        for (entry in openEntries.toList()) {
            val candidates = openLines.filter { sameMovement(entry, it) }
            val rivals = openEntries.filter { e -> candidates.any { sameMovement(e, it) } }
            if (candidates.size == 1 && rivals.size == 1) {
                matches += NostroMatch(entry, candidates.single(), MatchType.AMOUNT_DATE)
                openEntries.remove(entry)
                openLines.remove(candidates.single())
            }
        }
        return NostroReconciliation(
            statement = statement,
            glCode = glCode,
            ledgerOpeningBalance = ledgerOpeningBalance,
            ledgerClosingBalance = ledgerClosingBalance,
            matches = matches,
            unmatchedStatementEntries = openEntries,
            unmatchedLedgerLines = openLines,
            balanceNotStated = balanceNotStated,
        )
    }

    private fun sameMovement(entry: StatementEntry, line: LedgerNostroLine): Boolean =
        entry.amount.compareTo(line.amount) == 0 &&
            entry.currency == line.currency &&
            entry.bookingDate == line.entryDate &&
            (entry.direction == StatementDirection.CRDT) == (line.side == Side.DEBIT)

    private fun referenceAgrees(entry: StatementEntry, line: LedgerNostroLine): Boolean {
        val ref = entry.reference?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return ref.equals(line.transactionId.toString(), ignoreCase = true) ||
            line.description?.let { containsTokens(tokens(it), tokens(ref)) } == true
    }

    /** [needle] appears as a contiguous run of WHOLE tokens of [haystack], case-insensitively. */
    private fun containsTokens(haystack: List<String>, needle: List<String>): Boolean = needle.isNotEmpty() &&
        (0..haystack.size - needle.size).any { start ->
            needle.indices.all { i -> haystack[start + i].equals(needle[i], ignoreCase = true) }
        }

    /**
     * Whole whitespace-separated tokens, stripped of surrounding punctuation. A substring test
     * would let a short reference ("REF1") EXACT-match any description containing "REF12".
     */
    private fun tokens(description: String): List<String> =
        description.split(WHITESPACE).map { token -> token.trim { it in TRIM_PUNCTUATION } }.filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
    private const val TRIM_PUNCTUATION = ",;:.()[]\"'"
}
