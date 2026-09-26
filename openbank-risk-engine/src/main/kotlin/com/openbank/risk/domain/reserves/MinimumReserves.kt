// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.reserves

import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal

/**
 * What a GL account IS for the ČNB minimum reserve requirement (povinné minimální rezervy, PMR).
 * The trial balance does not say who a liability is owed to or when it matures, so the mapping is
 * configuration (`openbank.risk.min-reserves.classification.*`); an account it does not name is
 * listed as not classified — never counted, never dropped.
 *
 * Source: Opatření ČNB o povinných minimálních rezervách (Provision of the ČNB on minimum
 * reserves). Stated here only to the level of detail this code is confident of: the reserve base
 * is liabilities with an agreed maturity of up to 2 years, EXCLUDING liabilities to other banks
 * that are themselves subject to minimum reserves and liabilities to the ČNB. UNVERIFIED: the
 * exact enumeration of base items and exclusions in the provision's current wording (article
 * numbers are therefore not cited).
 */
enum class ReserveClass(val wire: String, val description: String) {
    RESERVE_BASE(
        "reserve-base",
        "Liability in the reserve base: to non-bank clients and others, agreed maturity up to 2 years (Opatření ČNB o PMR)",
    ),
    EXCLUDED_BANK_OR_CNB(
        "excluded-bank-or-cnb",
        "Liability excluded from the base: to a bank itself subject to minimum reserves, or to the ČNB (Opatření ČNB o PMR)",
    ),
    NOT_A_LIABILITY("not-a-liability", "Equity, income or expense: not a liability, so never in the reserve base"),
    RESERVE_HOLDING(
        "reserve-holding",
        "Balance on the bank's current account at the ČNB: the only thing that counts as reserve holding",
    ),
    NOT_A_HOLDING(
        "not-a-holding",
        "Asset that is not the ČNB current account (e.g. the ČNB deposit facility): never counted as holding",
    ),
    ;

    companion object {
        fun parse(raw: String): ReserveClass =
            entries.firstOrNull { it.wire == raw.trim().lowercase() || it.name == raw.trim() }
                ?: throw IllegalArgumentException(
                    "unknown minimum-reserve class '$raw'; one of ${entries.joinToString { it.wire }}",
                )
    }
}

/**
 * A versioned, cited parameter set (`openbank.risk.min-reserves.*`): every result names [id] and
 * [version].
 *
 *  - [rate]: the reserve ratio applied to the base — 2 % (Opatření ČNB o PMR).
 *  - [remunerationRate]: rate paid on the required reserves — 0 %: the ČNB has not remunerated
 *    minimum reserves since 2023-10-05.
 *  - [holdingCurrency]: the currency reserves are held in (the ČNB current account is in CZK).
 *  - [glAccounts] / [glAccountTypes]: GL code (or, failing that, GL account type) → class.
 */
data class MinReserveParameters(
    val id: String,
    val version: String,
    val source: String,
    val rate: BigDecimal,
    val remunerationRate: BigDecimal,
    val holdingCurrency: String,
    val glAccounts: Map<String, ReserveClass>,
    val glAccountTypes: Map<String, ReserveClass>,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "minimum-reserve parameter set needs an id and a version" }
        requireRate("rate", rate)
        requireRate("remuneration-rate", remunerationRate)
        require(holdingCurrency.isNotBlank()) { "minimum-reserve holding currency is required" }
    }

    fun classOf(glAccountCode: String?, glAccountType: String?): ReserveClass? =
        glAccountCode?.let { glAccounts[it] } ?: glAccountType?.let { glAccountTypes[it.uppercase()] }
}

private fun requireRate(name: String, value: BigDecimal) =
    require(value.signum() >= 0 && value <= BigDecimal.ONE) { "$name must lie in [0, 1], was $value" }

/** One contribution to the base or to holdings, positive = counts. */
data class ReserveLine(
    val label: String,
    val glAccountCode: String?,
    val amount: BigDecimal,
    val reserveClass: ReserveClass,
)

data class ExcludedLine(val glAccountCode: String?, val amount: BigDecimal, val reserveClass: ReserveClass)

/** A balance no configured class covers: listed, never counted, never dropped. */
data class UnclassifiedReserveBalance(
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val amount: BigDecimal,
    val reason: String,
)

data class CurrencyReserveBase(val currency: String, val lines: List<ReserveLine>, val rate: BigDecimal) {
    val base: BigDecimal get() = lines.sumOf { it.amount }
    val requirement: BigDecimal get() = base.multiply(rate, BigMath.MC)
}

data class MinReserveResult(
    val currencies: List<CurrencyReserveBase>,
    /** The single book currency, when there is exactly one; else null and no comparison is made. */
    val totalCurrency: String?,
    /** Null when no GL account is mapped as the ČNB current account: see [holdingsNotStated]. */
    val holdings: List<ReserveLine>?,
    /** Why holdings (and so the surplus) are not stated; null when they are. */
    val holdingsNotStated: String?,
    val holdingCurrency: String,
    val excluded: List<ExcludedLine>,
    val unclassified: List<UnclassifiedReserveBalance>,
    val remunerationRate: BigDecimal,
    val notes: List<String>,
) {
    val totalHoldings: BigDecimal? get() = holdings?.sumOf { it.amount }

    /** Requirement in the holding currency; null when the book has any other currency (no FX conversion). */
    val requirement: BigDecimal? get() =
        totalCurrency?.takeIf { it == holdingCurrency }?.let { c -> currencies.single { it.currency == c }.requirement }

    /** Holdings − requirement: positive is a surplus, negative a shortfall; null when either side is. */
    val surplus: BigDecimal? get() = requirement?.let { r -> totalHoldings?.subtract(r) }

    val remuneration: BigDecimal get() = (requirement ?: BigDecimal.ZERO).multiply(remunerationRate, BigMath.MC)
}

/**
 * The ČNB minimum reserve requirement of a tied-out snapshot (ADR-0313 treasury gap, ADR-0315):
 * reserve base × rate, compared with the balance on the ČNB current account.
 *
 * Positions are in the trial-balance sign convention (debit − credit), so a liability's base
 * contribution is the NEGATED amount, and a holding is the amount as is. A customer sub-ledger
 * balance in credit is a deposit (a liability to a non-bank client) and joins the base; one in
 * debit is an overdraft (an asset) and does not. Loans are assets and never join the base.
 *
 * A snapshot is ONE day. The requirement is met on the AVERAGE of end-of-day holdings over the
 * maintenance period, so the surplus reported here is that day's indicative position, not
 * compliance with the requirement — stated on every result.
 */
object MinimumReserves {

    const val AVERAGING_NOTE =
        "Minimum reserves are held on AVERAGE over the ČNB maintenance period; this is one snapshot day, so the " +
            "surplus / shortfall is indicative, not a compliance verdict. The maintenance-period calendar is not modelled."

    const val HOLDINGS_NOT_STATED =
        "No ledger GL account is mapped as the bank's current account at the ČNB (classification reserve-holding), " +
            "and the ledger chart has none; 1510 is the ČNB deposit facility, which does not hold reserves. " +
            "Holdings, surplus and shortfall are therefore not stated rather than reported as zero."

    const val CURRENCY_NOTE =
        "The base is reported per currency. Comparing it with holdings needs one currency; the engine has no " +
            "conversion to CZK yet, so requirement vs holdings is reported only for a CZK-only book."

    const val MATURITY_NOTE =
        "The ledger carries no agreed maturity for customer balances; every customer deposit is treated as maturing " +
            "within 2 years and so in the base (the conservative reading)."

    fun compute(positions: List<Position>, params: MinReserveParameters): MinReserveResult {
        val acc = Accumulator(params)
        positions.filter { it.amount.signum() != 0 }.forEach { p ->
            when (p.kind) {
                PositionKind.SUB_LEDGER -> acc.customer(p)
                PositionKind.LOAN -> Unit // an asset: never in the base, never a holding
                PositionKind.GL_ACCOUNT -> acc.glAccount(p)
            }
        }
        // A zero is only stated when an account exists that could hold a non-zero balance (ADR-0097).
        val holdingsStated = ReserveClass.RESERVE_HOLDING in params.glAccounts.values
        val bookCurrencies = positions.map { it.currency }.distinct().sorted()
        val single = bookCurrencies.singleOrNull()
        return MinReserveResult(
            currencies = bookCurrencies.map { CurrencyReserveBase(it, acc.baseLines(it), params.rate) },
            totalCurrency = single,
            holdings = acc.holdings.takeIf { holdingsStated },
            holdingsNotStated = HOLDINGS_NOT_STATED.takeUnless { holdingsStated },
            holdingCurrency = params.holdingCurrency,
            excluded = acc.excluded,
            unclassified = acc.unclassified,
            remunerationRate = params.remunerationRate,
            notes = listOfNotNull(
                AVERAGING_NOTE,
                MATURITY_NOTE,
                CURRENCY_NOTE.takeIf { single != params.holdingCurrency },
            ),
        )
    }

    private class Accumulator(private val params: MinReserveParameters) {
        val holdings = mutableListOf<ReserveLine>()
        val excluded = mutableListOf<ExcludedLine>()
        val unclassified = mutableListOf<UnclassifiedReserveBalance>()
        private val glBase = mutableMapOf<String, MutableList<ReserveLine>>()
        private val deposits = mutableMapOf<String, BigDecimal>()
        private val depositAccounts = mutableMapOf<String, Int>()

        /** Credit (negative) is a deposit, a liability to a non-bank client; debit is an overdraft, an asset. */
        fun customer(p: Position) {
            if (p.amount.signum() < 0) {
                deposits.merge(p.currency, p.amount.negate(), BigDecimal::add)
                depositAccounts.merge(p.currency, 1, Int::plus)
            }
        }

        fun glAccount(p: Position) {
            val cls = params.classOf(p.glAccountCode, p.glAccountType)
            when {
                cls == null -> unclassified(p, "GL account not mapped in openbank.risk.min-reserves.classification")
                cls == ReserveClass.RESERVE_BASE -> glBase.getOrPut(p.currency) { mutableListOf() } +=
                    ReserveLine("GL ${p.glAccountCode}", p.glAccountCode, p.amount.negate(), cls)
                cls != ReserveClass.RESERVE_HOLDING -> excluded += ExcludedLine(p.glAccountCode, p.amount, cls)
                p.currency == params.holdingCurrency ->
                    holdings += ReserveLine("GL ${p.glAccountCode}", p.glAccountCode, p.amount, cls)
                else -> unclassified(
                    p,
                    "reserve-holding account in ${p.currency}; reserves are held in ${params.holdingCurrency}",
                )
            }
        }

        private fun unclassified(p: Position, reason: String) {
            unclassified += UnclassifiedReserveBalance(p.glAccountCode, p.glAccountType, p.currency, p.amount, reason)
        }

        fun baseLines(currency: String): List<ReserveLine> {
            val customerLine = deposits[currency]?.let {
                ReserveLine(
                    "Customer deposits (${depositAccounts.getValue(currency)} accounts)",
                    null,
                    it,
                    ReserveClass.RESERVE_BASE,
                )
            }
            return listOfNotNull(customerLine) + glBase[currency].orEmpty()
        }
    }
}
