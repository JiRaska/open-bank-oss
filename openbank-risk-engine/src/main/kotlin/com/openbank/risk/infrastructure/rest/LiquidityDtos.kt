// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.LiquidityAnalysis
import com.openbank.risk.domain.liquidity.CurrencyLiquidity
import com.openbank.risk.domain.liquidity.GlClass
import com.openbank.risk.domain.liquidity.Liquidity
import com.openbank.risk.domain.liquidity.LiquidityFactor
import com.openbank.risk.domain.liquidity.LiquidityLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private const val MONEY_SCALE = 2

private fun BigDecimal.money(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)

data class LiquidityLineDto(
    val label: String,
    val glAccountCode: String?,
    val amount: BigDecimal,
    val factor: BigDecimal?,
    val factorKey: String?,
    val weighted: BigDecimal,
    val citation: String,
)

data class HqlaLineDto(
    val level: String,
    val glClass: String,
    val glAccountCode: String,
    val marketValue: BigDecimal,
    val haircut: BigDecimal,
    val afterHaircut: BigDecimal,
)

data class HqlaDto(
    val lines: List<HqlaLineDto>,
    val level1: BigDecimal,
    val level2a: BigDecimal,
    val level2b: BigDecimal,
    val adjustmentFor15Cap: BigDecimal,
    val adjustmentFor40Cap: BigDecimal,
    val level2bCapBinding: Boolean,
    val level2CapBinding: Boolean,
    val stock: BigDecimal,
)

data class LcrDto(
    val hqla: HqlaDto,
    val outflows: List<LiquidityLineDto>,
    val inflows: List<LiquidityLineDto>,
    val totalOutflows: BigDecimal,
    val totalInflows: BigDecimal,
    val inflowCap: BigDecimal,
    val cappedInflows: BigDecimal,
    val inflowCapBinding: Boolean,
    val netOutflows: BigDecimal,
    /** HQLA / net outflows; null when there are no net outflows. */
    val ratio: BigDecimal?,
)

data class NsfrDto(
    val asf: List<LiquidityLineDto>,
    val rsf: List<LiquidityLineDto>,
    val totalAsf: BigDecimal,
    val totalRsf: BigDecimal,
    /** ASF / RSF; null when nothing requires stable funding. */
    val ratio: BigDecimal?,
)

data class CurrencyLiquidityDto(val currency: String, val lcr: LcrDto, val nsfr: NsfrDto)

data class UnclassifiedBalanceDto(
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val amount: BigDecimal,
    val reason: String,
)

data class FactorDto(val key: String, val value: BigDecimal, val citation: String)

data class GlMappingDto(val key: String, val glClass: String, val description: String)

data class LiquidityClassificationDto(
    val retailStableShare: BigDecimal,
    val operationalDepositShare: BigDecimal,
    val tier2OverOneYearShare: BigDecimal,
    val loansQualifyForLowRiskWeight: Boolean,
    val glAccounts: List<GlMappingDto>,
    val glAccountTypes: List<GlMappingDto>,
    val choices: List<String>,
)

data class LiquidityAssumptionsDto(
    val parameterSetId: String,
    val parameterSetVersion: String,
    val source: String,
    val scope: String,
    val factors: List<FactorDto>,
    val classification: LiquidityClassificationDto,
    val hqlaCapMethod: String,
    val loanInflows: String,
    val loanRsf: String,
    val notInData: String,
    val currencyAggregation: String,
)

data class LiquidityResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currencies: List<CurrencyLiquidityDto>,
    /** Present only for a single-currency book. */
    val total: CurrencyLiquidityDto?,
    val unclassified: List<UnclassifiedBalanceDto>,
    val notes: List<String>,
    val assumptions: LiquidityAssumptionsDto,
)

fun LiquidityLine.toDto() =
    LiquidityLineDto(label, glAccountCode, amount.money(), factor, factorKey, weighted.money(), citation)

fun CurrencyLiquidity.toDto() = CurrencyLiquidityDto(
    currency = currency,
    lcr = LcrDto(
        hqla = HqlaDto(
            lines = lcr.hqla.lines.map {
                HqlaLineDto(
                    it.level.wire,
                    it.glClass.wire,
                    it.glAccountCode,
                    it.marketValue.money(),
                    it.haircut,
                    it.afterHaircut.money(),
                )
            },
            level1 = lcr.hqla.level1.money(),
            level2a = lcr.hqla.level2a.money(),
            level2b = lcr.hqla.level2b.money(),
            adjustmentFor15Cap = lcr.hqla.adjustmentFor15Cap.money(),
            adjustmentFor40Cap = lcr.hqla.adjustmentFor40Cap.money(),
            level2bCapBinding = lcr.hqla.level2bCapBinding,
            level2CapBinding = lcr.hqla.level2CapBinding,
            stock = lcr.hqla.stock.money(),
        ),
        outflows = lcr.outflows.map { it.toDto() },
        inflows = lcr.inflows.map { it.toDto() },
        totalOutflows = lcr.totalOutflows.money(),
        totalInflows = lcr.totalInflows.money(),
        inflowCap = lcr.inflowCap.money(),
        cappedInflows = lcr.cappedInflows.money(),
        inflowCapBinding = lcr.inflowCapBinding,
        netOutflows = lcr.netOutflows.money(),
        ratio = lcr.ratio,
    ),
    nsfr = NsfrDto(
        asf = nsfr.asf.map { it.toDto() },
        rsf = nsfr.rsf.map { it.toDto() },
        totalAsf = nsfr.totalAsf.money(),
        totalRsf = nsfr.totalRsf.money(),
        ratio = nsfr.ratio,
    ),
)

private fun GlClass.mapping(key: String) = GlMappingDto(key, wire, description)

fun LiquidityAnalysis.toResponse(): LiquidityResponse {
    val c = parameters.classification
    val perCurrency = result.currencies.map { it.toDto() }
    return LiquidityResponse(
        runId = run.id,
        asOf = run.asOf.toString(),
        provenance = run.provenance.wire,
        parameterSetId = parameters.id,
        parameterSetVersion = parameters.version,
        currencies = perCurrency,
        total = result.totalCurrency?.let { t -> perCurrency.single { it.currency == t } },
        unclassified = result.unclassified.map {
            UnclassifiedBalanceDto(it.glAccountCode, it.glAccountType, it.currency, it.amount.money(), it.reason)
        },
        notes = result.notes,
        assumptions = LiquidityAssumptionsDto(
            parameterSetId = parameters.id,
            parameterSetVersion = parameters.version,
            source = parameters.source,
            scope = "BCBS standard factors; EU CRR / Delegated Regulation (EU) 2015/61 deviations not applied.",
            factors = LiquidityFactor.entries.map { FactorDto(it.key, parameters[it], it.citation) },
            classification = LiquidityClassificationDto(
                retailStableShare = c.retailStableShare,
                operationalDepositShare = c.operationalDepositShare,
                tier2OverOneYearShare = c.tier2OverOneYearShare,
                loansQualifyForLowRiskWeight = c.loansQualifyForLowRiskWeight,
                glAccounts = c.glAccounts.toSortedMap().map { (k, v) -> v.mapping(k) },
                glAccountTypes = c.glAccountTypes.toSortedMap().map { (k, v) -> v.mapping(k) },
                choices = listOf(
                    "Customer balances on deposit control are treated as RETAIL deposits " +
                        "(natural persons, d238 ¶73): " +
                        "the snapshot carries no party type. Wholesale run-off (d238 ¶107-109: 40% / 100%) " +
                        "is not applied.",
                    "Stable share ${c.retailStableShare}: deposit-insurance coverage and relationships are not " +
                        "in the data, " +
                        "so d238 ¶80 places the whole amount in 'less stable' unless configured otherwise.",
                    "Operational share ${c.operationalDepositShare}: none unless configured (d238 ¶93-104).",
                    "Only GL accounts named in the mapping are classified; nothing else is counted, and every other " +
                        "non-zero balance is listed as not classified. Nostro at a commercial bank is never HQLA.",
                    "Customer overdrafts: open maturity, no LCR inflow (d238 ¶152); RSF as a loan ≥ 1 year.",
                ),
            ),
            hqlaCapMethod =
            "d238 Annex 1 ¶5: stock = L1 + L2A + L2B − adj(15%) − adj(40%), with 15/85, 15/60 and 2/3 " +
                "derived from the configured caps. No securities financing or collateral swaps in the " +
                "snapshot, so the " +
                "'adjusted' amounts (Annex 1 ¶4) equal the unadjusted ones.",
            loanInflows =
            "Contractual principal + interest of installments due in (asOf, asOf + 30 days] from lending's own " +
                "schedule, at the retail / non-financial rate (d238 ¶153); loans in IFRS 9 stage 3 are " +
                "treated as not " +
                "fully performing and give no inflow (d238 ¶142, ¶151). Borrowers are assumed not to be financial " +
                "institutions (the ¶154 100% rate would otherwise apply).",
            loanRsf = "Amortising split (d295 ¶29): principal due within one year at 50% (¶40(e)), the rest at 85% " +
                "(¶42(b)) unless loans are configured as ≤ 35% risk weight (65%, ¶41(b)); stage 3 as non-performing, " +
                "100% (¶43(c)) — a proxy for d295 footnote 19's > 90 days past due, which the data does not carry.",
            notInData =
            "No undrawn committed facilities (d238 ¶131), no term deposits, no issued debt, no derivatives and " +
                "no securities financing are in the snapshot: those outflow / inflow / RSF categories are absent, not zero-weighted.",
            currencyAggregation = Liquidity.AGGREGATION_NOTE,
        ),
    )
}
