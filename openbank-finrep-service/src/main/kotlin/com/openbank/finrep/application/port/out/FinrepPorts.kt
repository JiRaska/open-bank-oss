// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.out

import java.math.BigDecimal
import java.time.LocalDate

/**
 * One GL account line of the ledger trial balance.
 *
 * [net] is `totalDebit − totalCredit`, ledger's own uniform convention for every account type — so
 * it is NEGATIVE for a credit-normal account (liabilities, equity, income) and positive for a
 * debit-normal one (assets, expenses). The mappers are responsible for presenting each FINREP row
 * in its reporting sign; [com.openbank.finrep.domain.model.TrialBalanceIdentity] relies on the raw
 * convention being uniform, which is what makes `Σ net == 0` the double-entry identity.
 *
 * [currency] is carried because the identity holds PER CURRENCY. Without it the check could be
 * satisfied by a CZK line cancelling a lost EUR one (issue #5987). It carries NO default on
 * purpose: a defaulted `"CZK"` would let a response that omits the field deserialize into a
 * plausible-looking line, silently merging every currency into one residual bucket — the check
 * would then still pass, for a reason nothing anywhere would report.
 */
data class TrialBalanceLineDto(val code: String, val accountType: String, val net: BigDecimal, val currency: String)

/**
 * One ledger trial-balance read: the lines, plus the balance verdict the PRODUCER published with
 * them (issue #6011).
 *
 * [ledgerReportsBalanced] used to be deserialised and dropped on the floor in `LedgerAdapter`. It is
 * carried now because agreement between it and finrep's own recomputation is a check neither side
 * can make alone — see [com.openbank.finrep.domain.model.TrialBalanceAssurance] for the three
 * inputs that make them disagree.
 *
 * NULLABLE, and no default: `null` means the response carried no verdict, which is a different fact
 * from a verdict of `false`. A non-null `Boolean` here would let jackson-module-kotlin coerce an
 * absent field to `false` and report a contract change as an accounting failure; a default of `true`
 * would do the opposite and re-publish the producer's assertion without the producer.
 */
data class TrialBalanceSnapshot(val lines: List<TrialBalanceLineDto>, val ledgerReportsBalanced: Boolean?)

/** Minimal closed-period metadata needed to decide whether a regulatory render is reproducible. */
data class ClosedPeriodDto(val periodType: String, val to: LocalDate, val status: String, val evidenceState: String)

interface LedgerPort {
    /** Cumulative frozen closing balance for F01 and COREP stock cells. */
    suspend fun getTrialBalance(asOf: LocalDate): TrialBalanceSnapshot

    /** Mutable cumulative stock for an explicitly labelled internal working preview only. */
    suspend fun getLiveTrialBalance(asOf: LocalDate): TrialBalanceSnapshot

    /** One frozen MONTH's movements for F02 P&L; never a lifetime closing balance. */
    suspend fun getFrozenPeriodMovements(asOf: LocalDate): TrialBalanceSnapshot

    /** One mutable MONTH's movements for the F02 internal working preview. */
    suspend fun getLivePeriodMovements(asOf: LocalDate): TrialBalanceSnapshot

    /** Attested January-to-month-end F02 flow. */
    suspend fun getYearToDateMovements(asOf: LocalDate): TrialBalanceSnapshot

    /** Mutable January-to-exact-date F02 preview. */
    suspend fun getLiveYearToDateMovements(asOf: LocalDate): TrialBalanceSnapshot

    suspend fun listClosedPeriods(): List<ClosedPeriodDto>
}

/**
 * One exposure class of a risk-engine capital result (ADR-0313 D6): the class key as the engine
 * reports it (e.g. `sovereign-and-central-bank`) with its exposure and risk-weighted exposure.
 */
data class RiskExposureClass(val exposureClass: String, val ead: BigDecimal, val rwa: BigDecimal)

/**
 * The Pillar 1 standardised-approach credit-risk result of ONE tied-out risk-engine snapshot at
 * the report date. [classes] and [totalRwa] are present only for a single-currency book (the engine
 * does not convert currencies); [unclassifiedBalances] counts balances the engine could not
 * classify, so they carry no RWA.
 */
data class RiskCapitalResult(
    val runId: String,
    val asOf: LocalDate,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currency: String?,
    val classes: List<RiskExposureClass>,
    val totalRwa: BigDecimal?,
    val currencyCount: Int,
    val unclassifiedBalances: Int,
    /** The engine's own reason when it states no total (a missing ČNB fixing); null when it does. */
    val totalNotStated: String? = null,
    /** The source run's provenance (`synthetic` | `production`, ADR-0313 D13). */
    val provenance: String? = null,
)

/**
 * What a capital lookup found: the [result], or why there is none. [unavailableReason] is a
 * data-gap reason for the report, never an error: an unconfigured or empty source is a visible gap.
 */
data class RiskCapitalLookup(val result: RiskCapitalResult?, val unavailableReason: String?) {
    init {
        require((result == null) != (unavailableReason == null)) { "exactly one of result or reason" }
    }

    companion object {
        fun found(result: RiskCapitalResult) = RiskCapitalLookup(result, null)
        fun unavailable(reason: String) = RiskCapitalLookup(null, reason)
    }
}

/** Read-only view of the risk engine's capital result (ADR-0313 D6). */
interface RiskCapitalPort {
    /** The most recent TIED_OUT snapshot's capital at exactly [asOf], or why there is none. */
    suspend fun capitalAt(asOf: LocalDate): RiskCapitalLookup
}

/**
 * One HQLA line of a risk-engine LCR result (ADR-0313 phase 1): its level as the engine reports it
 * (`L1`, `L2A`, `L2B`), unweighted market value, the haircut the engine applied and the value after it.
 */
data class RiskHqlaLine(
    val level: String,
    val marketValue: BigDecimal,
    val haircut: BigDecimal,
    val afterHaircut: BigDecimal,
)

/**
 * The LCR of ONE tied-out risk-engine snapshot at the report date, read from the engine's combined
 * view: all currencies in CZK at the ČNB fixing (risk-engine API 1.13.0, EU 2015/61 Art. 4(5)). [currency]
 * and the figures are null when the engine states no combined view, [totalNotStated] then says why;
 * [unclassifiedBalances] counts balances the engine could not classify, any of which could be HQLA.
 * [notes] are the engine's free-text caveats on the result (e.g. that pledged collateral is not
 * modelled); the mappers read them through [com.openbank.finrep.domain.mapper.RiskEngineFigures].
 */
data class RiskLiquidityResult(
    val runId: String,
    val asOf: LocalDate,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val currency: String?,
    val lines: List<RiskHqlaLine>,
    val level1: BigDecimal?,
    val level2a: BigDecimal?,
    val level2b: BigDecimal?,
    val currencyCount: Int,
    val unclassifiedBalances: Int,
    val outflows: List<RiskOutflowLine> = emptyList(),
    val totalOutflows: BigDecimal? = null,
    val inflows: List<RiskInflowLine> = emptyList(),
    /** The engine's Σ weighted inflows, BEFORE the 75 % cap (C 74.00). */
    val totalInflows: BigDecimal? = null,
    /** The engine's cap amount: its cap factor × [totalOutflows]. */
    val inflowCap: BigDecimal? = null,
    /** The engine's inflows AFTER the cap: min([totalInflows], [inflowCap]). */
    val cappedInflows: BigDecimal? = null,
    val inflowCapBinding: Boolean? = null,
    val notes: List<String> = emptyList(),
    /** The engine's own reason when it states no combined total (e.g. a missing ČNB fixing); null when it does. */
    val totalNotStated: String? = null,
    /** The engine's Art. 17 adjustment for the 15 % Level 2B cap (Annex I ¶5), null when not reported (C 76.00). */
    val level2bCapAdjustment: BigDecimal? = null,
    /** The engine's Art. 17 adjustment for the 40 % Level 2 cap (Annex I ¶5), null when not reported (C 76.00). */
    val level2CapAdjustment: BigDecimal? = null,
    /** The engine's liquidity buffer: after haircuts AND after both Level 2 caps (C 76.00). */
    val hqlaStock: BigDecimal? = null,
    /** The engine's net liquidity outflows: [totalOutflows] − [cappedInflows] (C 76.00). */
    val netOutflows: BigDecimal? = null,
    /** The engine's LCR as a fraction ([hqlaStock] / [netOutflows], 6 dp); null when net outflows ≤ 0. */
    val lcrRatio: BigDecimal? = null,
    /** The source run's provenance (`synthetic` | `production`, ADR-0313 D13). */
    val provenance: String? = null,
)

/**
 * One LCR inflow line of a risk-engine result (COREP C 74.00): the inflow factor key the engine applied
 * (e.g. `lcr-retail-loan-inflow`), the unweighted [amount], the [factor] and the [weighted] inflow.
 */
data class RiskInflowLine(
    val factorKey: String,
    val amount: BigDecimal,
    val factor: BigDecimal,
    val weighted: BigDecimal,
)

/**
 * One LCR outflow line of a risk-engine result (COREP C 73.00): the run-off factor key the engine
 * applied (e.g. `lcr-retail-stable-runoff`), the unweighted [amount], the [factor] and the
 * [weighted] outflow. [RiskLiquidityResult.totalOutflows] is the engine's own sum of [weighted].
 */
data class RiskOutflowLine(
    val factorKey: String,
    val amount: BigDecimal,
    val factor: BigDecimal,
    val weighted: BigDecimal,
)

/** What a liquidity lookup found: the [result], or the data-gap reason there is none (never an error). */
data class RiskLiquidityLookup(val result: RiskLiquidityResult?, val unavailableReason: String?) {
    init {
        require((result == null) != (unavailableReason == null)) { "exactly one of result or reason" }
    }

    companion object {
        fun found(result: RiskLiquidityResult) = RiskLiquidityLookup(result, null)
        fun unavailable(reason: String) = RiskLiquidityLookup(null, reason)
    }
}

/**
 * Read-only view of the risk engine's LCR result (COREP C 72.00 liquid assets, C 73.00 outflows, C 74.00
 * inflows, C 76.00 calculation). The run is selected
 * exactly as [RiskCapitalPort] selects it, so C 02.00 and C 72.00 of one date read the same snapshot.
 */
interface RiskLiquidityPort {
    /** The most recent TIED_OUT snapshot's liquid assets at exactly [asOf], or why there are none. */
    suspend fun liquidityAt(asOf: LocalDate): RiskLiquidityLookup
}
