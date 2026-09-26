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
    suspend fun getTrialBalance(asOf: LocalDate): TrialBalanceSnapshot

    /** Mutable period aggregate for an explicitly labelled internal working preview only. */
    suspend fun getLiveTrialBalance(asOf: LocalDate): TrialBalanceSnapshot

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
 * The liquid-asset side of ONE tied-out risk-engine snapshot's LCR at the report date. [lines] and
 * the level sums (after haircut, before the Level 2 caps) are present only for a single-currency book;
 * [unclassifiedBalances] counts balances the engine could not classify, any of which could be HQLA.
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
 * Read-only view of the risk engine's LCR liquid-asset result (COREP C 72.00). The run is selected
 * exactly as [RiskCapitalPort] selects it, so C 02.00 and C 72.00 of one date read the same snapshot.
 */
interface RiskLiquidityPort {
    /** The most recent TIED_OUT snapshot's liquid assets at exactly [asOf], or why there are none. */
    suspend fun liquidityAt(asOf: LocalDate): RiskLiquidityLookup
}
