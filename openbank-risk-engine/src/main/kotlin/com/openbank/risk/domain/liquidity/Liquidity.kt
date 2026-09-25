// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * One weighted line of a component table: [amount] (the balance, positive) × [factor] = [weighted].
 * [factor] is null only for a line reported for completeness and not weighted (e.g. a capital
 * deduction excluded from ASF by d295 ¶17); [citation] then says why.
 */
data class LiquidityLine(
    val label: String,
    val glAccountCode: String?,
    val amount: BigDecimal,
    val factor: BigDecimal?,
    val factorKey: String?,
    val citation: String,
) {
    val weighted: BigDecimal get() = factor?.let { amount.multiply(it, BigMath.MC) } ?: BigDecimal.ZERO
}

enum class HqlaLevel(val wire: String) { LEVEL_1("L1"), LEVEL_2A("L2A"), LEVEL_2B("L2B") }

data class HqlaLine(
    val level: HqlaLevel,
    val glClass: GlClass,
    val glAccountCode: String,
    val marketValue: BigDecimal,
    val haircut: BigDecimal,
) {
    val afterHaircut: BigDecimal get() = marketValue.multiply(BigDecimal.ONE.subtract(haircut), BigMath.MC)
}

/**
 * The stock of HQLA after the d238 Annex 1 caps. The snapshot carries no securities financing or
 * collateral-swap transactions, so the "adjusted" amounts Annex 1 ¶4 defines (after unwinding
 * those within 30 days) equal the unadjusted ones — stated in the assumptions block.
 */
data class HqlaStock(
    val lines: List<HqlaLine>,
    val level1: BigDecimal,
    val level2a: BigDecimal,
    val level2b: BigDecimal,
    val adjustmentFor15Cap: BigDecimal,
    val adjustmentFor40Cap: BigDecimal,
) {
    val stock: BigDecimal get() = level1.add(
        level2a,
    ).add(level2b).subtract(adjustmentFor15Cap).subtract(adjustmentFor40Cap)
    val level2bCapBinding: Boolean get() = adjustmentFor15Cap.signum() > 0
    val level2CapBinding: Boolean get() = adjustmentFor40Cap.signum() > 0
}

data class LcrResult(
    val hqla: HqlaStock,
    val outflows: List<LiquidityLine>,
    val inflows: List<LiquidityLine>,
    val inflowCapFactor: BigDecimal,
) {
    val totalOutflows: BigDecimal get() = outflows.sumOf { it.weighted }
    val totalInflows: BigDecimal get() = inflows.sumOf { it.weighted }
    val inflowCap: BigDecimal get() = totalOutflows.multiply(inflowCapFactor, BigMath.MC)
    val cappedInflows: BigDecimal get() = totalInflows.min(inflowCap)
    val inflowCapBinding: Boolean get() = totalInflows > inflowCap
    val netOutflows: BigDecimal get() = totalOutflows.subtract(cappedInflows)

    /** HQLA / net outflows; null when there are no net outflows (the ratio is undefined, not infinite). */
    val ratio: BigDecimal? get() =
        if (netOutflows.signum() <=
            0
        ) {
            null
        } else {
            hqla.stock.divide(netOutflows, BigMath.MC).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN)
        }
}

data class NsfrResult(val asf: List<LiquidityLine>, val rsf: List<LiquidityLine>) {
    val totalAsf: BigDecimal get() = asf.sumOf { it.weighted }
    val totalRsf: BigDecimal get() = rsf.sumOf { it.weighted }

    /** ASF / RSF; null when nothing requires stable funding. */
    val ratio: BigDecimal? get() =
        if (totalRsf.signum() <=
            0
        ) {
            null
        } else {
            totalAsf.divide(totalRsf, BigMath.MC).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN)
        }
}

data class CurrencyLiquidity(val currency: String, val lcr: LcrResult, val nsfr: NsfrResult)

/** A balance no configured class covers: listed, never counted, never dropped. */
data class UnclassifiedBalance(
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val amount: BigDecimal,
    val reason: String,
)

data class LiquidityResult(
    val currencies: List<CurrencyLiquidity>,
    /** The single book currency, when there is exactly one; else null and no total is reported. */
    val totalCurrency: String?,
    val unclassified: List<UnclassifiedBalance>,
    val notes: List<String>,
) {
    val total: CurrencyLiquidity? get() = totalCurrency?.let { c -> currencies.single { it.currency == c } }
}

private const val RATIO_SCALE = 6
private const val LCR_HORIZON_DAYS = 30L
private const val NSFR_HORIZON_YEARS = 1L
private const val STAGE_3 = "STAGE_3"

/**
 * LCR (BCBS d238) and NSFR (BCBS d295) of a tied-out snapshot — BCBS standard factors, no EU CRR /
 * Delegated Regulation (EU) 2015/61 deviations. Per currency; a total only for a single-currency
 * book (the engine has no reporting-currency conversion yet — the same rule as IRRBB).
 */
object Liquidity {

    const val AGGREGATION_NOTE =
        "Computed per currency. A total across currencies needs conversion to one reporting currency, which " +
            "the engine does not do yet, so the total is reported only for a single-currency book."

    fun compute(
        positions: List<Position>,
        instruments: List<Instrument>,
        asOf: LocalDate,
        params: LiquidityParameters,
    ): LiquidityResult {
        val byId = instruments.associateBy { it.id }
        val unclassified = mutableListOf<UnclassifiedBalance>()
        val currencies = positions.map { it.currency }.distinct().sorted().map { ccy ->
            val acc = Accumulator(params)
            positions.filter { it.currency == ccy }.forEach { p ->
                when (p.kind) {
                    PositionKind.SUB_LEDGER -> acc.customerAccount(p)
                    PositionKind.LOAN -> acc.loan(p, p.instrumentId?.let(byId::get), asOf)
                    PositionKind.GL_ACCOUNT -> {
                        val cls = params.classification.classOf(p.glAccountCode, p.glAccountType)
                        if (cls == null) {
                            if (p.amount.signum() != 0) {
                                unclassified += UnclassifiedBalance(
                                    p.glAccountCode,
                                    p.glAccountType,
                                    p.currency,
                                    p.amount,
                                    "GL account not mapped in openbank.risk.liquidity.classification",
                                )
                            }
                        } else {
                            acc.glAccount(p, cls)
                        }
                    }
                }
            }
            acc.finishDeposits()
            CurrencyLiquidity(ccy, acc.lcr(), NsfrResult(acc.asf, acc.rsf))
        }
        return LiquidityResult(
            currencies = currencies,
            totalCurrency = currencies.singleOrNull()?.currency,
            unclassified = unclassified,
            notes = listOfNotNull(AGGREGATION_NOTE.takeIf { currencies.size > 1 }),
        )
    }

    /** d238 Annex 1 ¶5, with the 2/3, 15/85 and 15/60 ratios derived from the two configured caps. */
    fun hqlaStock(lines: List<HqlaLine>, level2Cap: BigDecimal, level2bCap: BigDecimal): HqlaStock {
        fun sum(level: HqlaLevel) = lines.filter { it.level == level }.sumOf { it.afterHaircut }
        val l1 = sum(HqlaLevel.LEVEL_1)
        val l2a = sum(HqlaLevel.LEVEL_2A)
        val l2b = sum(HqlaLevel.LEVEL_2B)
        val nonL2 = BigDecimal.ONE.subtract(level2Cap) // 60%
        val nonL2b = BigDecimal.ONE.subtract(level2bCap) // 85%
        val l2bOverAll = level2bCap.divide(nonL2b, BigMath.MC) // 15/85
        val l2bOverL1 = level2bCap.divide(nonL2, BigMath.MC) // 15/60
        val l2OverL1 = level2Cap.divide(nonL2, BigMath.MC) // 2/3
        val adj15 = maxOf(
            l2b.subtract(l2bOverAll.multiply(l1.add(l2a), BigMath.MC)),
            l2b.subtract(l2bOverL1.multiply(l1, BigMath.MC)),
            BigDecimal.ZERO,
        )
        val adj40 = l2a.add(l2b).subtract(adj15).subtract(l2OverL1.multiply(l1, BigMath.MC)).max(BigDecimal.ZERO)
        return HqlaStock(lines, l1, l2a, l2b, adj15, adj40)
    }

    private class Accumulator(private val params: LiquidityParameters) {
        val hqla = mutableListOf<HqlaLine>()
        val outflows = mutableListOf<LiquidityLine>()
        val inflows = mutableListOf<LiquidityLine>()
        val asf = mutableListOf<LiquidityLine>()
        val rsf = mutableListOf<LiquidityLine>()
        private var deposits = BigDecimal.ZERO
        private var depositAccounts = 0
        private var overdrafts = BigDecimal.ZERO
        private var overdraftAccounts = 0
        private val cls get() = params.classification

        private fun line(label: String, code: String?, amount: BigDecimal, f: LiquidityFactor) =
            LiquidityLine(label, code, amount, params[f], f.key, f.citation)

        /** A customer balance in the trial-balance convention: credit (negative) is a deposit, debit an overdraft. */
        fun customerAccount(p: Position) {
            if (p.amount.signum() < 0) {
                deposits = deposits.add(p.amount.negate())
                depositAccounts++
            } else if (p.amount.signum() > 0) {
                overdrafts = overdrafts.add(p.amount)
                overdraftAccounts++
            }
        }

        fun finishDeposits() {
            if (depositAccounts > 0) {
                val operational = deposits.multiply(cls.operationalDepositShare, BigMath.MC)
                val retail = deposits.subtract(operational)
                val stable = retail.multiply(cls.retailStableShare, BigMath.MC)
                val lessStable = retail.subtract(stable)
                val n = "$depositAccounts customer accounts"
                if (stable.signum() > 0) {
                    outflows +=
                        line("Retail deposits, stable ($n)", null, stable, LiquidityFactor.LCR_RETAIL_STABLE_RUNOFF)
                    asf += line("Retail deposits, stable ($n)", null, stable, LiquidityFactor.NSFR_ASF_RETAIL_STABLE)
                }
                if (lessStable.signum() > 0) {
                    outflows +=
                        line(
                            "Retail deposits, less stable ($n)",
                            null,
                            lessStable,
                            LiquidityFactor.LCR_RETAIL_LESS_STABLE_RUNOFF,
                        )
                    asf +=
                        line(
                            "Retail deposits, less stable ($n)",
                            null,
                            lessStable,
                            LiquidityFactor.NSFR_ASF_RETAIL_LESS_STABLE,
                        )
                }
                if (operational.signum() > 0) {
                    outflows +=
                        line(
                            "Operational deposits ($n)",
                            null,
                            operational,
                            LiquidityFactor.LCR_OPERATIONAL_DEPOSIT_RUNOFF,
                        )
                    asf +=
                        line(
                            "Operational deposits ($n)",
                            null,
                            operational,
                            LiquidityFactor.NSFR_ASF_OPERATIONAL_DEPOSIT,
                        )
                }
            }
            if (overdraftAccounts > 0) {
                // Open-maturity lending: no LCR inflow (d238 ¶152); maturity undefined, so the ≥ 1 year RSF.
                rsf += line("Overdrawn customer accounts ($overdraftAccounts)", null, overdrafts, loanOverOneYear())
            }
        }

        private fun loanOverOneYear() = if (cls.loansQualifyForLowRiskWeight) {
            LiquidityFactor.NSFR_RSF_LOAN_1Y_LOW_RW
        } else {
            LiquidityFactor.NSFR_RSF_LOAN_1Y_OTHER
        }

        fun loan(p: Position, instrument: Instrument?, asOf: LocalDate) {
            val label = "Loan ${p.instrumentId ?: "?"}"
            val outstanding = p.amount
            if (instrument?.ifrs9Stage == STAGE_3) {
                // Not fully performing: no inflow (d238 ¶142, ¶151), non-performing RSF (d295 ¶43(c)).
                rsf +=
                    line(
                        "$label (non-performing, $STAGE_3)",
                        p.glAccountCode,
                        outstanding,
                        LiquidityFactor.NSFR_RSF_OTHER_ASSET,
                    )
                return
            }
            val installments = (instrument?.extension as? LoanExtension)?.remainingInstallments.orEmpty()
            val lcrEnd = asOf.plusDays(LCR_HORIZON_DAYS)
            val nsfrEnd = asOf.plusYears(NSFR_HORIZON_YEARS)
            val due30 = installments.filter { it.dueDate > asOf && it.dueDate <= lcrEnd }
                .fold(BigDecimal.ZERO) { a, i -> a.add(i.principal).add(i.interest) }
            val bulletIn30 = installments.isEmpty() && instrument?.maturityDate?.let { it <= lcrEnd } == true
            val inflow = if (bulletIn30) outstanding else due30
            if (inflow.signum() > 0) {
                inflows +=
                    line(
                        "$label: contractual payments due ≤ 30 days",
                        p.glAccountCode,
                        inflow,
                        LiquidityFactor.LCR_RETAIL_LOAN_INFLOW,
                    )
            }
            val under1y = if (installments.isEmpty()) {
                if (instrument?.maturityDate?.let { it <= nsfrEnd } == true) outstanding else BigDecimal.ZERO
            } else {
                installments.filter {
                    it.dueDate <= nsfrEnd
                }.fold(BigDecimal.ZERO) { a, i -> a.add(i.principal) }.min(outstanding)
            }
            val over1y = outstanding.subtract(under1y)
            if (under1y.signum() > 0) {
                rsf +=
                    line(
                        "$label: principal due < 1 year",
                        p.glAccountCode,
                        under1y,
                        LiquidityFactor.NSFR_RSF_LOAN_UNDER_1Y,
                    )
            }
            if (over1y.signum() > 0) {
                rsf += line("$label: principal due ≥ 1 year", p.glAccountCode, over1y, loanOverOneYear())
            }
        }

        @Suppress("CyclomaticComplexMethod")
        fun glAccount(p: Position, c: GlClass) {
            val code = requireNotNull(p.glAccountCode) { "a GL-level position always names its account" }
            val label = "GL $code (${c.wire})"
            val asset = p.amount
            val liability = p.amount.negate()
            when (c) {
                GlClass.HQLA_L1_CASH_OR_RESERVES -> {
                    hqla += HqlaLine(HqlaLevel.LEVEL_1, c, code, asset, params[LiquidityFactor.LCR_L1_HAIRCUT])
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_CASH_AND_RESERVES)
                }
                GlClass.HQLA_L1_SECURITIES -> {
                    hqla += HqlaLine(HqlaLevel.LEVEL_1, c, code, asset, params[LiquidityFactor.LCR_L1_HAIRCUT])
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_L1_SECURITIES)
                }
                GlClass.HQLA_L2A -> {
                    hqla += HqlaLine(HqlaLevel.LEVEL_2A, c, code, asset, params[LiquidityFactor.LCR_L2A_HAIRCUT])
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_L2A)
                }
                GlClass.HQLA_L2B_RMBS -> {
                    hqla += HqlaLine(HqlaLevel.LEVEL_2B, c, code, asset, params[LiquidityFactor.LCR_L2B_RMBS_HAIRCUT])
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_L2B)
                }
                GlClass.HQLA_L2B_OTHER -> {
                    hqla += HqlaLine(HqlaLevel.LEVEL_2B, c, code, asset, params[LiquidityFactor.LCR_L2B_OTHER_HAIRCUT])
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_L2B)
                }
                GlClass.DEPOSIT_AT_FI_OPERATIONAL -> {
                    inflows += line(label, code, asset, LiquidityFactor.LCR_OPERATIONAL_DEPOSIT_INFLOW)
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_OPERATIONAL_DEPOSIT_AT_FI)
                }
                GlClass.DEPOSIT_AT_FI_NON_OPERATIONAL -> {
                    inflows += line(label, code, asset, LiquidityFactor.LCR_FI_INFLOW)
                    rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_FI_UNDER_6M)
                }
                GlClass.OTHER_ASSET -> rsf += line(label, code, asset, LiquidityFactor.NSFR_RSF_OTHER_ASSET)
                GlClass.CAPITAL -> asf += line(label, code, liability, LiquidityFactor.NSFR_ASF_CAPITAL)
                GlClass.CAPITAL_DEDUCTION ->
                    asf +=
                        LiquidityLine(
                            label,
                            code,
                            liability,
                            null,
                            null,
                            "BCBS d295 ¶17, ¶21(a): ASF counts capital before deductions",
                        )
                GlClass.CAPITAL_TIER2 -> {
                    val longPart = liability.multiply(cls.tier2OverOneYearShare, BigMath.MC)
                    asf += line("$label, residual maturity ≥ 1 year", code, longPart, LiquidityFactor.NSFR_ASF_CAPITAL)
                    asf +=
                        line(
                            "$label, residual maturity < 1 year or unknown",
                            code,
                            liability.subtract(longPart),
                            LiquidityFactor.NSFR_ASF_OTHER,
                        )
                }
                GlClass.OTHER_LIABILITY -> {
                    outflows += line(label, code, liability, LiquidityFactor.LCR_OTHER_CONTRACTUAL_OUTFLOW)
                    asf += line(label, code, liability, LiquidityFactor.NSFR_ASF_OTHER)
                }
                GlClass.CURRENT_YEAR_RESULT -> asf += line(label, code, liability, LiquidityFactor.NSFR_ASF_OTHER)
            }
        }

        fun lcr() = LcrResult(
            hqla = hqlaStock(hqla, params[LiquidityFactor.LCR_LEVEL2_CAP], params[LiquidityFactor.LCR_LEVEL2B_CAP]),
            outflows = outflows,
            inflows = inflows,
            inflowCapFactor = params[LiquidityFactor.LCR_INFLOW_CAP],
        )
    }
}
