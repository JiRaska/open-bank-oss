// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * One instrument of a custodian's period-end statement of holdings (semt.002 `BalForAcct`), as the
 * custodian stated it, before classification.
 */
data class CustodyHolding(
    val isin: String,
    /** ISO 10962 CFI; null when the custodian stated none (the statement is then refused). */
    val cfi: String?,
    val quantity: BigDecimal,
    /** The holding value in the account's base currency, as stated. */
    val valuation: BigDecimal,
    val valuationCurrency: String,
)

/** A complete semt.002 statement of holdings for one safekeeping account at [statementDate]. */
data class CustodyStatement(
    val statementId: String?,
    val safekeepingAccount: String,
    val statementDate: LocalDate,
    val holdings: List<CustodyHolding>,
)

/** One classified position of a stored portfolio snapshot. */
data class PortfolioPosition(
    val isin: String,
    val cfi: String,
    val instrumentClass: String,
    val quantity: BigDecimal,
    val valuation: BigDecimal,
    val valuationCurrency: String,
) {
    init {
        require(Isin.isValid(isin)) { "ISIN $isin is not a valid ISO 6166 identifier" }
        require(quantity.signum() > 0) { "ISIN $isin has a non-positive quantity $quantity" }
        require(valuation.signum() >= 0) { "ISIN $isin has a negative valuation $valuation" }
    }
}

/**
 * The portfolio of [entity] at [statementDate]: every holding the custodian stated, classified. All
 * valuations are in ONE [currency] (the safekeeping account's base currency) — a total over mixed
 * currencies would be a figure nobody could stand behind, so a mixed statement is refused.
 */
data class PortfolioSnapshot(
    val entity: String,
    val statementId: String?,
    val safekeepingAccount: String,
    val statementDate: LocalDate,
    val currency: String,
    val positions: List<PortfolioPosition>,
) {
    init {
        require(positions.all { it.valuationCurrency == currency }) {
            "every position must be valued in the statement currency $currency"
        }
        require(positions.map { it.isin }.toSet().size == positions.size) { "a snapshot states each ISIN once" }
    }
}

/**
 * Instrument class from the CFI code (ADR-0337 amendment D3): a DECLARED prefix mapping, longest
 * prefix wins. Never a guess — a CFI no prefix covers refuses the whole statement, because a
 * holding the return cannot classify is a holding the return would misreport.
 */
class CfiClassMapping(prefixes: Map<String, String>) {
    private val prefixes: List<Pair<String, String>> = prefixes
        .map { (prefix, cls) -> prefix.trim().uppercase() to cls.trim() }
        .onEach { (prefix, cls) ->
            require(prefix.matches(PREFIX)) { "CFI prefix '$prefix' must be 1-6 letters" }
            require(cls.isNotEmpty()) { "CFI prefix '$prefix' maps to an empty class" }
        }
        .sortedByDescending { it.first.length }

    fun classOf(cfi: String): String? = prefixes.firstOrNull { cfi.startsWith(it.first) }?.second

    /**
     * Classifies every holding, or refuses the statement naming EVERY holding that cannot be
     * classified (so one upload attempt shows the whole gap, not the first line of it).
     */
    fun classify(entity: String, statement: CustodyStatement, currency: String): PortfolioSnapshot {
        val unmapped = statement.holdings.filter { h -> h.cfi?.let(::classOf) == null }
        require(unmapped.isEmpty()) {
            "statement ${statement.statementId ?: statement.safekeepingAccount} for ${statement.statementDate} " +
                "is refused: no instrument-class mapping for " +
                unmapped.joinToString { "${it.isin} (CFI ${it.cfi ?: "not stated"})" } +
                " — declare the CFI prefix in openbank.treasury.portfolio.cfi-classes"
        }
        return PortfolioSnapshot(
            entity = entity,
            statementId = statement.statementId,
            safekeepingAccount = statement.safekeepingAccount,
            statementDate = statement.statementDate,
            currency = currency,
            positions = statement.holdings.map { h ->
                val cfi = requireNotNull(h.cfi)
                PortfolioPosition(
                    h.isin,
                    cfi,
                    requireNotNull(classOf(cfi)),
                    h.quantity,
                    h.valuation,
                    h.valuationCurrency,
                )
            },
        )
    }

    private companion object {
        val PREFIX = Regex("[A-Z]{1,6}")
    }
}

/** ISO 6166 ISIN: format and the Luhn check digit over the letters-as-numbers expansion. */
object Isin {
    private val FORMAT = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
    private const val RADIX = 36
    private const val DECIMAL = 10

    fun isValid(isin: String): Boolean {
        if (!FORMAT.matches(isin)) return false
        val digits = isin.dropLast(1).map { it.digitToInt(RADIX) }.joinToString("")
        val sum = digits.reversed().mapIndexed { i, c ->
            val n = c.digitToInt() * if (i % 2 == 0) 2 else 1
            n / DECIMAL + n % DECIMAL
        }.sum()
        return (DECIMAL - sum % DECIMAL) % DECIMAL == isin.last().digitToInt()
    }
}
