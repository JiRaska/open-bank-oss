// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.math.RoundingMode

/** One weighted exposure: [ead] (on-balance carrying amount, positive) × [riskWeight] = [rwa]. */
data class ExposureLine(
    val exposureClass: ExposureClass,
    val label: String,
    val glAccountCode: String?,
    val instrumentId: String?,
    val ead: BigDecimal,
    val riskWeight: BigDecimal,
    val factorKey: String,
    val citation: String,
    /** IFRS 9 stage reported for a loan line; null for an account-level line or when not reported. */
    val ifrs9Stage: String? = null,
) {
    val rwa: BigDecimal get() = ead.multiply(riskWeight, BigMath.MC)
}

data class ClassTotal(val exposureClass: ExposureClass, val ead: BigDecimal, val rwa: BigDecimal)

/** One own-funds account as booked, and the amount it contributes (positive adds, negative deducts). */
data class OwnFundsLine(val glAccountCode: String, val glClass: CapitalGlClass, val contribution: BigDecimal)

/**
 * Own funds read from the capital GL accounts. Trial-balance sign convention: equity is a credit
 * (negative), so every contribution is the negated balance — and a DEBIT balance on the deduction
 * account comes out negative, i.e. deducted, exactly as #10860 treats 6040.
 */
data class OwnFunds(val lines: List<OwnFundsLine>) {
    private fun sum(vararg c: CapitalGlClass) = lines.filter { it.glClass in c }.sumOf { it.contribution }
    val cet1BeforeDeductions: BigDecimal get() = sum(CapitalGlClass.OWN_FUNDS_CET1)
    val cet1Deductions: BigDecimal get() = sum(CapitalGlClass.OWN_FUNDS_CET1_DEDUCTION)
    val cet1: BigDecimal get() = cet1BeforeDeductions.add(cet1Deductions)
    val at1: BigDecimal get() = sum(CapitalGlClass.OWN_FUNDS_AT1)
    val tier1: BigDecimal get() = cet1.add(at1)
    val tier2: BigDecimal get() = sum(CapitalGlClass.OWN_FUNDS_TIER2)
    val total: BigDecimal get() = tier1.add(tier2)
}

data class CurrencyCapital(
    val currency: String,
    val lines: List<ExposureLine>,
    /** Own-funds accounts booked in this currency; null when there are none. */
    val ownFunds: OwnFunds?,
) {
    val classes: List<ClassTotal> get() = lines.groupBy { it.exposureClass }.toSortedMap().map { (c, ls) ->
        ClassTotal(c, ls.sumOf { it.ead }, ls.sumOf { it.rwa })
    }
    val totalEad: BigDecimal get() = lines.sumOf { it.ead }
    val totalRwa: BigDecimal get() = lines.sumOf { it.rwa }
}

data class CapitalRatio(val ratio: BigDecimal, val minimum: BigDecimal, val minimumCitation: String) {
    val meetsMinimum: Boolean get() = ratio >= minimum
}

data class CapitalRatios(val cet1: CapitalRatio, val tier1: CapitalRatio, val total: CapitalRatio)

/** A balance no configured class covers (or that cannot be weighted): listed, never counted, never dropped. */
data class UnclassifiedCapitalBalance(
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val amount: BigDecimal,
    val reason: String,
)

data class CapitalResult(
    val currencies: List<CurrencyCapital>,
    /** The CZK total at the ČNB fixing ([ReportingCurrencyTotal]); null with [totalNotStated] when a rate is missing. */
    val total: CurrencyCapital?,
    /** Every rate the total was converted with; empty for an all-CZK book. */
    val fxRates: List<FxRateUsed>,
    /** Why [total] is null; null when it is stated. */
    val totalNotStated: String?,
    /** Own-funds requirement of the total: [CapitalFactor.MIN_TOTAL_CAPITAL_RATIO] × total RWA. */
    val ownFundsRequirement: BigDecimal?,
    val ratios: CapitalRatios?,
    /** Why [ratios] is null; null when they were computed. */
    val ratiosNotComputable: String?,
    val unclassified: List<UnclassifiedCapitalBalance>,
    val notes: List<String>,
    /** Machine-readable form of [ratiosNotComputable], so a client can localise it. */
    val ratiosNotComputableCode: RatiosNotComputable? = null,
)

/** Why the capital ratios were not computed; [wire] is the API code, [message] the English reason. */
enum class RatiosNotComputable(val wire: String, val message: String) {
    MULTI_CURRENCY("multi-currency", "multi-currency book: own funds are not converted to one currency"),
    NO_POSITIONS("no-positions", "the snapshot has no positions"),
    NO_OWN_FUNDS(
        "no-own-funds",
        "no own-funds GL account (openbank.risk.capital.sa.classification own-funds-*) is in the snapshot",
    ),
    ZERO_RWA("zero-rwa", "credit-risk RWA is zero: a ratio is undefined"),
}

private const val RATIO_SCALE = 6
private const val STAGE_3 = "STAGE_3"

/**
 * Pillar 1 credit-risk RWA under the standardised approach of the selected [CapitalParameters]
 * ([CapitalRegime.EU]: CRR Part Three Title II Chapter 2, the default; [CapitalRegime.BCBS]: d424 Part I),
 * and the capital ratios where
 * own funds are in the snapshot (ADR-0313 phase 2). Per currency, plus a CZK total and requirement
 * at the ČNB fixing ([ReportingCurrencyTotal]); ratios only for a single-currency book, since own
 * funds are not converted. No credit-risk mitigation (none is in the snapshot) and no off-balance
 * items (none in the snapshot, so no CCF is applied).
 */
object CreditRiskCapital {

    const val AGGREGATION_NOTE =
        "Computed per currency. The total is in CZK: each other currency's EAD and RWA are converted at the " +
            "ČNB fixing in effect on the as-of date (the fixing whose validity window contains 00:00 Prague of " +
            "that day, the same rule as the ledger's FX revaluation; a weekend or holiday takes the prior " +
            "business day's fixing while it is still valid), listed in fxRates. If any needed fixing is missing " +
            "no total is stated (never a partial one). Own funds are not converted, so the ratios are reported " +
            "only for a single-currency book."

    const val CREDIT_RISK_ONLY_NOTE =
        "The ratios divide own funds by CREDIT-RISK RWA only. The minima (CRR Art. 92(1); bcbs189 ¶50) apply to " +
            "the total risk exposure amount, which also " +
            "includes operational and market risk (and CVA); those are not computed here, so these ratios are an " +
            "UPPER BOUND on the real ones."

    fun compute(
        positions: List<Position>,
        instruments: List<Instrument>,
        params: CapitalParameters,
        fixings: Map<String, FxRateUsed> = emptyMap(),
        asOf: java.time.LocalDate? = null,
    ): CapitalResult {
        val byId = instruments.associateBy { it.id }
        val unclassified = mutableListOf<UnclassifiedCapitalBalance>()
        val currencies = positions.map { it.currency }.distinct().sorted().map { ccy ->
            val acc = Accumulator(ccy, params, unclassified)
            positions.filter { it.currency == ccy }.forEach { p ->
                when (p.kind) {
                    PositionKind.SUB_LEDGER -> acc.customerAccount(p)
                    PositionKind.LOAN -> acc.loan(p, p.instrumentId?.let(byId::get)?.ifrs9Stage)
                    // A money-market deal is classified by the principal account it sits on (1510
                    // central bank, 1500/1501 bank, 2300/2301 a liability), exactly as its GL-level
                    // balance was before deals were modelled (ADR-0315 D6).
                    PositionKind.GL_ACCOUNT, PositionKind.TREASURY_DEAL -> acc.glAccount(p)
                }
            }
            CurrencyCapital(ccy, acc.lines, acc.ownFunds.takeIf { it.isNotEmpty() }?.let(::OwnFunds))
        }
        val reporting = ReportingCurrencyTotal.of(currencies, fixings, asOf)
        val requirement = reporting.total?.totalRwa?.multiply(params[CapitalFactor.MIN_TOTAL_CAPITAL_RATIO], BigMath.MC)
        val total = currencies.singleOrNull()
        val notComputable = when {
            currencies.size > 1 -> RatiosNotComputable.MULTI_CURRENCY
            total == null -> RatiosNotComputable.NO_POSITIONS
            total.ownFunds == null -> RatiosNotComputable.NO_OWN_FUNDS
            total.totalRwa.signum() <= 0 -> RatiosNotComputable.ZERO_RWA
            else -> null
        }
        val ratios = if (notComputable == null) ratios(total!!, params) else null
        return CapitalResult(
            currencies = currencies,
            total = reporting.total,
            fxRates = reporting.fxRates,
            totalNotStated = reporting.notStated,
            ownFundsRequirement = requirement,
            ratios = ratios,
            ratiosNotComputable = notComputable?.message,
            ratiosNotComputableCode = notComputable,
            unclassified = unclassified,
            notes = listOfNotNull(
                AGGREGATION_NOTE.takeIf {
                    currencies.any {
                        it.currency !=
                            ReportingCurrencyTotal.REPORTING_CURRENCY
                    }
                },
                CREDIT_RISK_ONLY_NOTE,
            ),
        )
    }

    private const val NEGATIVE_EXPOSURE =
        "Credit balance on an exposure account: not an exposure, and not netted against other exposures (no CRM)"

    private fun weightOf(c: CapitalGlClass, ccy: String, p: CapitalParameters): Pair<ExposureClass, CapitalFactor> =
        when (c) {
            CapitalGlClass.CENTRAL_BANK ->
                ExposureClass.SOVEREIGN to
                    if (ccy == p.classification.domesticCurrency) {
                        CapitalFactor.RW_SOVEREIGN_DOMESTIC_CURRENCY
                    } else {
                        CapitalFactor.RW_SOVEREIGN_UNRATED
                    }
            CapitalGlClass.BANK -> ExposureClass.BANK to p.classification.bankScraGrade.factor
            CapitalGlClass.RETAIL ->
                p.classification.retailTreatment.exposureClass to p.classification.retailTreatment.factor
            CapitalGlClass.CASH -> ExposureClass.CASH to CapitalFactor.RW_CASH
            CapitalGlClass.CASH_ITEMS_IN_COLLECTION ->
                ExposureClass.CASH_ITEMS_IN_COLLECTION to CapitalFactor.RW_CASH_ITEMS_IN_COLLECTION
            CapitalGlClass.OTHER_ASSET -> ExposureClass.OTHER_ASSET to CapitalFactor.RW_OTHER_ASSET
            else -> error("${c.wire} is not an exposure class")
        }

    private fun ratios(t: CurrencyCapital, p: CapitalParameters): CapitalRatios {
        val of = t.ownFunds!!
        fun r(amount: BigDecimal, min: CapitalFactor) = CapitalRatio(
            amount.divide(t.totalRwa, BigMath.MC).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN),
            p[min],
            p.citation(min),
        )
        return CapitalRatios(
            cet1 = r(of.cet1, CapitalFactor.MIN_CET1_RATIO),
            tier1 = r(of.tier1, CapitalFactor.MIN_TIER1_RATIO),
            total = r(of.total, CapitalFactor.MIN_TOTAL_CAPITAL_RATIO),
        )
    }

    private class Accumulator(
        private val ccy: String,
        private val params: CapitalParameters,
        private val unclassified: MutableList<UnclassifiedCapitalBalance>,
    ) {
        val lines = mutableListOf<ExposureLine>()
        val ownFunds = mutableListOf<OwnFundsLine>()
        private val cls get() = params.classification

        private fun line(c: ExposureClass, label: String, p: Position, f: CapitalFactor, stage: String? = null) {
            lines +=
                ExposureLine(
                    c,
                    label,
                    p.glAccountCode,
                    p.instrumentId,
                    p.amount,
                    params[f],
                    f.key,
                    params.citation(f),
                    stage,
                )
        }

        private fun unclassify(p: Position, reason: String) {
            unclassified += UnclassifiedCapitalBalance(p.glAccountCode, p.glAccountType, p.currency, p.amount, reason)
        }

        private fun retail(label: String, p: Position, stage: String? = null) =
            line(cls.retailTreatment.exposureClass, label, p, cls.retailTreatment.factor, stage)

        /** Credit balance = a deposit (a liability, no exposure); debit = an overdraft, retail (¶55 names overdrafts). */
        fun customerAccount(p: Position) {
            if (p.amount.signum() > 0) retail("Overdrawn customer account", p)
        }

        /** IFRS 9 stage 3 is the proxy for d424 ¶90 default; anything else is the configured retail treatment. */
        fun loan(p: Position, stage: String?) {
            val label = "Loan ${p.instrumentId ?: "?"} (${stage ?: "IFRS 9 stage not reported"})"
            when {
                p.amount.signum() < 0 -> unclassify(p, NEGATIVE_EXPOSURE)
                p.amount.signum() == 0 -> Unit
                stage == STAGE_3 -> line(ExposureClass.DEFAULTED, label, p, CapitalFactor.RW_DEFAULTED, stage)
                else -> retail(label, p, stage)
            }
        }

        fun glAccount(p: Position) {
            val c = cls.classOf(p.glAccountCode, p.glAccountType)
            when {
                p.amount.signum() == 0 || c == CapitalGlClass.NOT_AN_EXPOSURE -> Unit
                c == null -> unclassify(p, "GL account not mapped in openbank.risk.capital.sa.classification")
                c.isOwnFunds -> ownFundsAccount(p, c)
                p.amount.signum() < 0 -> unclassify(p, NEGATIVE_EXPOSURE)
                else -> {
                    val (ec, f) = weightOf(c, ccy, params)
                    line(ec, "GL ${p.glAccountCode} (${c.wire}, GL level)", p, f)
                }
            }
        }

        private fun ownFundsAccount(p: Position, c: CapitalGlClass) {
            if (c == CapitalGlClass.OWN_FUNDS_CET1_DEDUCTION && p.amount.signum() < 0) {
                unclassify(p, "Credit balance on a CET1 deduction account would ADD to CET1: not counted")
            } else {
                ownFunds += OwnFundsLine(requireNotNull(p.glAccountCode), c, p.amount.negate())
            }
        }
    }
}
