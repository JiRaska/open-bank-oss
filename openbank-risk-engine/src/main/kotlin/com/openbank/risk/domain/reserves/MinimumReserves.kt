// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.reserves

import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.time.LocalDate

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
 * A ČNB minimum-reserve FACT in effect on a day — the reserve ratio or its remuneration — as
 * published by fx-service (`fx.cnb-policy-rate.published.v1`) with its provenance. [rate] is a
 * fraction (0.04 = 4 %).
 */
data class ReserveRateFact(
    val instrument: String,
    val effectiveFrom: LocalDate,
    val rate: BigDecimal,
    val sourceUrl: String,
    val contentSha256: String,
    val note: String?,
) {
    init {
        requireRate(instrument, rate)
    }

    companion object {
        const val RATIO = "MIN_RESERVE_RATIO"
        const val REMUNERATION = "MIN_RESERVE_REMUNERATION"
    }
}

/**
 * A versioned, cited parameter set (`openbank.risk.min-reserves.*`): every result names [id] and
 * [version].
 *
 *  - [ratio] / [remuneration]: the ČNB facts in effect on the evaluated day, never constants. They
 *    come from fx-service's ČNB ingestion via [withFacts]; the configured set carries none. While
 *    either is missing the requirement is NOT stated ([ratesNotStated]) — a defaulted ratio is a
 *    number nobody can stand behind (ADR-0097), and the ratio has changed (2 % -> 4 % on 2025-01-02).
 *  - [holdingCurrency]: the currency reserves are held in (the ČNB current account is in CZK).
 *  - [glAccounts] / [glAccountTypes]: GL code (or, failing that, GL account type) → class.
 */
data class MinReserveParameters(
    val id: String,
    val version: String,
    val source: String,
    val holdingCurrency: String,
    val glAccounts: Map<String, ReserveClass>,
    val glAccountTypes: Map<String, ReserveClass>,
    val ratio: ReserveRateFact? = null,
    val remuneration: ReserveRateFact? = null,
    /** The day the facts were resolved for; null on the configured set before [withFacts]. */
    val factsAsOf: LocalDate? = null,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "minimum-reserve parameter set needs an id and a version" }
        require(holdingCurrency.isNotBlank()) { "minimum-reserve holding currency is required" }
        require(ratio == null || ratio.instrument == ReserveRateFact.RATIO) {
            "ratio fact must be ${ReserveRateFact.RATIO}"
        }
        require(remuneration == null || remuneration.instrument == ReserveRateFact.REMUNERATION) {
            "remuneration fact must be ${ReserveRateFact.REMUNERATION}"
        }
    }

    /** The reserve ratio in effect, or null — never a default. */
    val rate: BigDecimal? get() = ratio?.rate

    /** The remuneration rate in effect, or null — never a default. */
    val remunerationRate: BigDecimal? get() = remuneration?.rate

    /** Why the requirement cannot be evaluated; null when both facts are in effect. */
    val ratesNotStated: String?
        get() {
            val missing = listOfNotNull(
                ReserveRateFact.RATIO.takeIf { ratio == null },
                ReserveRateFact.REMUNERATION.takeIf { remuneration == null },
            )
            if (missing.isEmpty()) return null
            val day = factsAsOf?.toString() ?: "the evaluated day"
            return "NOT_EVALUABLE: no ČNB ${missing.joinToString(" / ")} fact is in effect on $day " +
                "(fx-service ČNB policy-rate feed, fx.cnb-policy-rate.published.v1), so the requirement is not " +
                "stated rather than computed at a default rate."
        }

    fun withFacts(asOf: LocalDate, ratio: ReserveRateFact?, remuneration: ReserveRateFact?): MinReserveParameters =
        copy(ratio = ratio, remuneration = remuneration, factsAsOf = asOf)

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

/**
 * The reserve base of one currency. [base] is null, with [baseNotStated] saying why, while any
 * LIABILITY balance in that currency is unclassified: the base would then be a partial sum, and a
 * partial sum reported as the base is a number nobody can stand behind (ADR-0097). [requirement] is
 * null when the base is, and also when no reserve ratio is in effect ([rateNotStated]).
 */
data class CurrencyReserveBase(
    val currency: String,
    val lines: List<ReserveLine>,
    val rate: BigDecimal?,
    val baseNotStated: String? = null,
    val rateNotStated: String? = null,
) {
    val base: BigDecimal? get() = if (baseNotStated == null) lines.sumOf { it.amount } else null
    val requirement: BigDecimal? get() = rate?.let { r -> base?.multiply(r, BigMath.MC) }

    /** Why [requirement] is not stated: the base gap first, then the missing ratio; null when it is. */
    val requirementNotStated: String? get() = baseNotStated ?: rateNotStated.takeIf { rate == null }
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
    /** Null when no remuneration fact is in effect — see [MinReserveParameters.ratesNotStated]. */
    val remunerationRate: BigDecimal?,
    val notes: List<String>,
) {
    val totalHoldings: BigDecimal? get() = holdings?.sumOf { it.amount }

    /**
     * Requirement on the holding-currency book; null when that currency's book has an unclassified
     * liability (see [CurrencyReserveBase.requirementNotStated]) or the book has no such currency.
     * Other currencies do not null it: each currency's base stands on its own classification.
     */
    val requirement: BigDecimal? get() = currencies.singleOrNull { it.currency == holdingCurrency }?.requirement

    /**
     * Holdings − requirement: positive is a surplus, negative a shortfall. Only for a book entirely in
     * the holding currency — with another currency in the book the requirement on it is not converted
     * (no FX yet), so comparing holdings with the holding-currency requirement alone would overstate
     * the surplus. Null when either side is.
     */
    val surplus: BigDecimal? get() =
        requirement?.takeIf { totalCurrency == holdingCurrency }?.let { r -> totalHoldings?.subtract(r) }

    val remuneration: BigDecimal? get() = remunerationRate?.let {
        (requirement ?: BigDecimal.ZERO).multiply(it, BigMath.MC)
    }
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
            "surplus / shortfall is indicative, not a compliance verdict. The period average is served by " +
            "GET /api/v1/risk/min-reserves/periods/{periodId}."

    const val HOLDINGS_NOT_STATED =
        "No ledger GL account is mapped as the bank's current account at the ČNB (classification reserve-holding), " +
            "and the ledger chart has none; 1510 is the ČNB deposit facility, which does not hold reserves. " +
            "Holdings, surplus and shortfall are therefore not stated rather than reported as zero."

    const val CURRENCY_NOTE =
        "The base is reported per currency. Comparing it with holdings needs one currency; the engine has no " +
            "conversion to CZK yet, so each currency's requirement is stated on its own and requirement vs holdings " +
            "(surplus / shortfall) is reported only for a CZK-only book."

    const val UNCLASSIFIED_LIABILITY =
        "A LIABILITY balance in this currency is not classified (see unclassified), so the base would be a partial " +
            "sum; base and requirement are not stated rather than understated."

    const val MATURITY_NOTE =
        "The ledger carries no agreed maturity for customer balances; every customer deposit is treated as maturing " +
            "within 2 years and so in the base (the conservative reading)."

    private const val LIABILITY = "LIABILITY"

    fun compute(positions: List<Position>, params: MinReserveParameters): MinReserveResult {
        val acc = Accumulator(params)
        positions.filter { it.amount.signum() != 0 }.forEach { p ->
            when (p.kind) {
                PositionKind.SUB_LEDGER -> acc.customer(p)
                PositionKind.LOAN -> Unit // an asset: never in the base, never a holding
                // A money-market deal is classified by its principal account (2300/2301 borrowing,
                // 1510 facility), exactly as its GL-level balance was before deals were modelled
                // (ADR-0315 D6) — the same routing Liquidity and CreditRiskCapital use.
                PositionKind.GL_ACCOUNT, PositionKind.TREASURY_DEAL -> acc.glAccount(p)
            }
        }
        // A zero is only stated when an account exists that could hold a non-zero balance (ADR-0097).
        val holdingsStated = ReserveClass.RESERVE_HOLDING in params.glAccounts.values
        val bookCurrencies = positions.map { it.currency }.distinct().sorted()
        val single = bookCurrencies.singleOrNull()
        return MinReserveResult(
            currencies = bookCurrencies.map { c ->
                val gap = acc.unclassified.any {
                    it.currency == c &&
                        it.glAccountType.equals(LIABILITY, ignoreCase = true)
                }
                CurrencyReserveBase(
                    currency = c,
                    lines = acc.baseLines(c),
                    rate = params.rate,
                    baseNotStated = UNCLASSIFIED_LIABILITY.takeIf { gap },
                    rateNotStated = params.ratesNotStated,
                )
            },
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
