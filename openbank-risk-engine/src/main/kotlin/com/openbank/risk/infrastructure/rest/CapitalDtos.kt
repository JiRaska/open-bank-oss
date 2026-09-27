// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.CapitalAnalysis
import com.openbank.risk.domain.capital.CapitalFactor
import com.openbank.risk.domain.capital.CapitalGlClass
import com.openbank.risk.domain.capital.CapitalRatio
import com.openbank.risk.domain.capital.CreditRiskCapital
import com.openbank.risk.domain.capital.CurrencyCapital
import com.openbank.risk.domain.capital.OwnFunds
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private const val CAPITAL_MONEY_SCALE = 2

private fun BigDecimal.cash(): BigDecimal = setScale(CAPITAL_MONEY_SCALE, RoundingMode.HALF_EVEN)

data class ExposureLineDto(
    val exposureClass: String,
    val label: String,
    val glAccountCode: String?,
    val instrumentId: String?,
    val ead: BigDecimal,
    val riskWeight: BigDecimal,
    val rwa: BigDecimal,
    val factorKey: String,
    val citation: String,
)

data class ExposureClassDto(
    val exposureClass: String,
    val ead: BigDecimal,
    val rwa: BigDecimal,
    val citations: List<String>,
)

data class OwnFundsLineDto(val glAccountCode: String, val glClass: String, val contribution: BigDecimal)

data class OwnFundsDto(
    val lines: List<OwnFundsLineDto>,
    val cet1BeforeDeductions: BigDecimal,
    val cet1Deductions: BigDecimal,
    val cet1: BigDecimal,
    val at1: BigDecimal,
    val tier1: BigDecimal,
    val tier2: BigDecimal,
    val total: BigDecimal,
)

data class CurrencyCapitalDto(
    val currency: String,
    val classes: List<ExposureClassDto>,
    val lines: List<ExposureLineDto>,
    val totalEad: BigDecimal,
    val totalRwa: BigDecimal,
    val ownFunds: OwnFundsDto?,
)

data class CapitalRatioDto(
    val ratio: BigDecimal,
    val minimum: BigDecimal,
    val meetsMinimum: Boolean,
    val citation: String,
)

data class CapitalRatiosDto(val cet1: CapitalRatioDto, val tier1: CapitalRatioDto, val total: CapitalRatioDto)

data class CapitalClassificationDto(
    val retailTreatment: String,
    val bankScraGrade: String,
    val domesticCurrency: String,
    val glAccounts: List<GlMappingDto>,
    val glAccountTypes: List<GlMappingDto>,
    val choices: List<String>,
)

data class CapitalAssumptionsDto(
    val parameterSetId: String,
    val parameterSetVersion: String,
    val source: String,
    val scope: String,
    val factors: List<FactorDto>,
    val classification: CapitalClassificationDto,
    val exposureValue: String,
    val creditRiskMitigation: String,
    val offBalanceSheet: String,
    val defaulted: String,
    val ownFunds: String,
    val creditRiskOnly: String,
    val currencyAggregation: String,
)

data class CapitalResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currencies: List<CurrencyCapitalDto>,
    /** Present only for a single-currency book. */
    val total: CurrencyCapitalDto?,
    /** min-total-capital-ratio × total RWA; present only with [total]. */
    val ownFundsRequirement: BigDecimal?,
    val ratios: CapitalRatiosDto?,
    val ratiosNotComputable: String?,
    val unclassified: List<UnclassifiedBalanceDto>,
    val notes: List<String>,
    val assumptions: CapitalAssumptionsDto,
)

private fun OwnFunds.toDto() = OwnFundsDto(
    lines = lines.map { OwnFundsLineDto(it.glAccountCode, it.glClass.wire, it.contribution.cash()) },
    cet1BeforeDeductions = cet1BeforeDeductions.cash(),
    cet1Deductions = cet1Deductions.cash(),
    cet1 = cet1.cash(),
    at1 = at1.cash(),
    tier1 = tier1.cash(),
    tier2 = tier2.cash(),
    total = total.cash(),
)

fun CurrencyCapital.toDto() = CurrencyCapitalDto(
    currency = currency,
    classes = classes.map { c ->
        ExposureClassDto(
            c.exposureClass.wire,
            c.ead.cash(),
            c.rwa.cash(),
            lines.filter { it.exposureClass == c.exposureClass }.map { it.citation }.distinct(),
        )
    },
    lines = lines.map {
        ExposureLineDto(
            it.exposureClass.wire,
            it.label,
            it.glAccountCode,
            it.instrumentId,
            it.ead.cash(),
            it.riskWeight,
            it.rwa.cash(),
            it.factorKey,
            it.citation,
        )
    },
    totalEad = totalEad.cash(),
    totalRwa = totalRwa.cash(),
    ownFunds = ownFunds?.toDto(),
)

private fun CapitalRatio.toDto() = CapitalRatioDto(ratio, minimum, meetsMinimum, minimumCitation)

private fun CapitalGlClass.mapping(key: String) = GlMappingDto(key, wire, description)

fun CapitalAnalysis.toResponse(): CapitalResponse {
    val perCurrency = result.currencies.map { it.toDto() }
    return CapitalResponse(
        runId = run.id,
        asOf = run.asOf.toString(),
        provenance = run.provenance.wire,
        parameterSetId = parameters.id,
        parameterSetVersion = parameters.version,
        currencies = perCurrency,
        total = result.totalCurrency?.let { t -> perCurrency.single { it.currency == t } },
        ownFundsRequirement = result.ownFundsRequirement?.cash(),
        ratios = result.ratios?.let { CapitalRatiosDto(it.cet1.toDto(), it.tier1.toDto(), it.total.toDto()) },
        ratiosNotComputable = result.ratiosNotComputable,
        unclassified = result.unclassified.map {
            UnclassifiedBalanceDto(it.glAccountCode, it.glAccountType, it.currency, it.amount.cash(), it.reason)
        },
        notes = result.notes,
        assumptions = assumptions(),
    )
}

@Suppress("LongMethod") // one statement per assumption; splitting it would only scatter them
private fun CapitalAnalysis.assumptions(): CapitalAssumptionsDto {
    val c = parameters.classification
    return CapitalAssumptionsDto(
        parameterSetId = parameters.id,
        parameterSetVersion = parameters.version,
        source = parameters.source,
        scope = "BCBS d424 SA weights; EU CRR Part Three Title II Chapter 2 not applied. Pillar 1 credit risk only.",
        factors = CapitalFactor.entries.map { FactorDto(it.key, parameters[it], it.citation) },
        classification = CapitalClassificationDto(
            retailTreatment = c.retailTreatment.wire,
            bankScraGrade = c.bankScraGrade.name,
            domesticCurrency = c.domesticCurrency,
            glAccounts = c.glAccounts.toSortedMap().map { (k, v) -> v.mapping(k) },
            glAccountTypes = c.glAccountTypes.toSortedMap().map { (k, v) -> v.mapping(k) },
            choices = listOf(
                "Loans and overdrafts are '${c.retailTreatment.wire}' (${c.retailTreatment.factor.citation}): the " +
                    "d424 ¶55 regulatory-retail criteria (product, EUR 1m per obligor, 0.2% granularity) and ¶54's " +
                    "natural-person test cannot be verified from the snapshot.",
                "Every bank exposure is unrated and weighted at SCRA Grade ${c.bankScraGrade.name} " +
                    "(${c.bankScraGrade.factor.citation}): no external ratings or counterparty capital disclosures " +
                    "are in the data (¶23, ¶26). Short-term weights (¶30) need original maturities a GL balance " +
                    "does not carry; the ¶31 sovereign floor needs the counterparty's jurisdiction, and at Grade C " +
                    "(150%, the table maximum) it cannot bind.",
                "Central-bank claims in ${c.domesticCurrency} take the d424 ¶8 national-discretion weight " +
                    "(${parameters[CapitalFactor.RW_SOVEREIGN_DOMESTIC_CURRENCY]}); " +
                    "in any other currency the unrated ¶7 weight " +
                    "(${parameters[CapitalFactor.RW_SOVEREIGN_UNRATED]}). " +
                    "The sovereign's rating is not in the data.",
                "Only GL accounts named in the mapping are weighted; any other asset balance is listed as not " +
                    "classified and counted nowhere, so RWA is understated by exactly those balances.",
            ),
        ),
        exposureValue =
        "EAD = on-balance carrying amount of each position / loan instrument in the tied-out snapshot " +
            "(d424 ¶92 'net of specific provisions' cannot be applied: the loan loss allowance is not allocated to loans).",
        creditRiskMitigation = "None applied. Loan collateral exists in lending but is not on the snapshot, and no " +
            "guarantees, credit derivatives or netting agreements are in the data (d424 Part I section D not used).",
        offBalanceSheet =
        "None in the snapshot (no undrawn commitments, guarantees or letters of credit), so no credit " +
            "conversion factor (d424 ¶78-85) is applied: the category is absent, not zero-weighted.",
        defaulted = "IFRS 9 stage 3 is the documented proxy for d424 ¶90 default (> 90 days past due or unlikely to " +
            "pay). Specific provisions per loan are not in the data, so ¶92's 150% (provisions < 20%) applies; " +
            "the 100% bucket needs per-loan provisions. A loan read at GL level carries no stage and is weighted as retail.",
        ownFunds = "Own funds from the capital GL accounts as booked: CET1 = 6000-6030 less the 6040 deduction " +
            "(bcbs189 ¶52, ¶66-89 as already booked); AT1 = 6050 (¶54); Tier 2 = 6060 (¶57). No further regulatory " +
            "adjustment, no Tier 2 amortisation, and no current-year result (INCOME / EXPENSE not closed) is applied.",
        creditRiskOnly = CreditRiskCapital.CREDIT_RISK_ONLY_NOTE,
        currencyAggregation = CreditRiskCapital.AGGREGATION_NOTE,
    )
}
