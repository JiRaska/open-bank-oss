// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import java.math.BigDecimal

/**
 * Every regulatory factor the LCR and NSFR apply, with the paragraph it comes from.
 *
 * The VALUES are not here: they arrive from configuration (`openbank.risk.liquidity.factors`), and
 * [LiquidityParameters] refuses a parameter set that is missing any of these keys or carries one
 * that is not listed. So a factor is never a code default, and every factor applied has a citation.
 *
 * d238 = BCBS, "Basel III: The Liquidity Coverage Ratio and liquidity risk monitoring tools",
 * January 2013. d295 = BCBS, "Basel III: the net stable funding ratio", October 2014.
 */
enum class LiquidityFactor(val key: String, val citation: String) {
    LCR_L1_HAIRCUT("lcr-l1-haircut", "BCBS d238 ¶49 (Level 1 not subject to a haircut)"),
    LCR_L2A_HAIRCUT("lcr-l2a-haircut", "BCBS d238 ¶52 (15% haircut on Level 2A)"),
    LCR_L2B_RMBS_HAIRCUT("lcr-l2b-rmbs-haircut", "BCBS d238 ¶54(a) (25% haircut on qualifying RMBS)"),
    LCR_L2B_OTHER_HAIRCUT("lcr-l2b-other-haircut", "BCBS d238 ¶54(b),(c) (50% haircut, corporate debt / equities)"),
    LCR_LEVEL2_CAP("lcr-level2-cap", "BCBS d238 ¶46, ¶51, Annex 1 (Level 2 ≤ 40% of the stock)"),
    LCR_LEVEL2B_CAP("lcr-level2b-cap", "BCBS d238 ¶47, Annex 1 (Level 2B ≤ 15% of the stock)"),
    LCR_RETAIL_STABLE_RUNOFF("lcr-retail-stable-runoff", "BCBS d238 ¶75 (stable retail, 5%; ¶78 allows 3%)"),
    LCR_RETAIL_LESS_STABLE_RUNOFF("lcr-retail-less-stable-runoff", "BCBS d238 ¶79 (less stable retail, min 10%)"),
    LCR_OPERATIONAL_DEPOSIT_RUNOFF("lcr-operational-deposit-runoff", "BCBS d238 ¶93 (operational deposits, 25%)"),
    LCR_OTHER_CONTRACTUAL_OUTFLOW("lcr-other-contractual-outflow", "BCBS d238 ¶141 (other contractual outflows, 100%)"),
    LCR_RETAIL_LOAN_INFLOW("lcr-retail-loan-inflow", "BCBS d238 ¶153 (retail / small business inflows, 50%)"),
    LCR_FI_INFLOW("lcr-fi-inflow", "BCBS d238 ¶154 (financial-institution inflows, 100%)"),
    LCR_OPERATIONAL_DEPOSIT_INFLOW(
        "lcr-operational-deposit-inflow",
        "BCBS d238 ¶156, ¶98 (operational deposits held at other institutions, 0%)",
    ),
    LCR_INFLOW_CAP("lcr-inflow-cap", "BCBS d238 ¶69, ¶144 (inflows capped at 75% of outflows)"),
    NSFR_ASF_CAPITAL("nsfr-asf-capital", "BCBS d295 ¶21(a) (regulatory capital before deductions, 100%)"),
    NSFR_ASF_RETAIL_STABLE("nsfr-asf-retail-stable", "BCBS d295 ¶22 (stable retail deposits, 95%)"),
    NSFR_ASF_RETAIL_LESS_STABLE("nsfr-asf-retail-less-stable", "BCBS d295 ¶23 (less stable retail deposits, 90%)"),
    NSFR_ASF_OPERATIONAL_DEPOSIT("nsfr-asf-operational-deposit", "BCBS d295 ¶24(b) (operational deposits, 50%)"),
    NSFR_ASF_OTHER("nsfr-asf-other", "BCBS d295 ¶25 (all other liabilities and equity, 0%)"),
    NSFR_RSF_CASH_AND_RESERVES("nsfr-rsf-cash-and-reserves", "BCBS d295 ¶36(a),(b) (coins, banknotes, reserves, 0%)"),
    NSFR_RSF_L1_SECURITIES("nsfr-rsf-l1-securities", "BCBS d295 ¶37 (other unencumbered Level 1, 5%)"),
    NSFR_RSF_L2A("nsfr-rsf-l2a", "BCBS d295 ¶39(a) (unencumbered Level 2A, 15%)"),
    NSFR_RSF_L2B("nsfr-rsf-l2b", "BCBS d295 ¶40(a) (unencumbered Level 2B, 50%)"),
    NSFR_RSF_FI_UNDER_6M("nsfr-rsf-fi-under-6m", "BCBS d295 ¶39(b) (other claims on FIs < 6 months, 15%)"),
    NSFR_RSF_OPERATIONAL_DEPOSIT_AT_FI(
        "nsfr-rsf-operational-deposit-at-fi",
        "BCBS d295 ¶40(d) (operational deposits held at other FIs, 50%)",
    ),
    NSFR_RSF_LOAN_UNDER_1Y(
        "nsfr-rsf-loan-under-1y",
        "BCBS d295 ¶40(e), ¶29 (non-HQLA < 1 year incl. retail loans, 50%)",
    ),
    NSFR_RSF_LOAN_1Y_LOW_RW("nsfr-rsf-loan-1y-low-rw", "BCBS d295 ¶41(b) (loans ≥ 1 year, RW ≤ 35%, 65%)"),
    NSFR_RSF_LOAN_1Y_OTHER("nsfr-rsf-loan-1y-other", "BCBS d295 ¶42(b) (performing loans ≥ 1 year, RW > 35%, 85%)"),
    NSFR_RSF_OTHER_ASSET(
        "nsfr-rsf-other-asset",
        "BCBS d295 ¶43(c) (all other assets incl. non-performing loans, 100%)",
    ),
    ;

    companion object {
        fun byKey(key: String): LiquidityFactor? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a GL account IS for liquidity purposes. Nothing in the trial balance says whether an asset
 * is central-bank money or a claim on a commercial bank, so the mapping is configuration
 * (`openbank.risk.liquidity.classification.gl-accounts`), and an account it does not name is
 * reported as not classified — never counted, never dropped.
 */
enum class GlClass(val wire: String, val description: String) {
    HQLA_L1_CASH_OR_RESERVES(
        "hqla-l1-cash-or-reserves",
        "Level 1: coins, banknotes, drawable central-bank reserves (d238 ¶50(a),(b))",
    ),
    HQLA_L1_SECURITIES("hqla-l1-securities", "Level 1: 0%-RW sovereign / central-bank securities (d238 ¶50(c)-(e))"),
    HQLA_L2A("hqla-l2a", "Level 2A (d238 ¶52)"),
    HQLA_L2B_RMBS("hqla-l2b-rmbs", "Level 2B: qualifying RMBS (d238 ¶54(a))"),
    HQLA_L2B_OTHER("hqla-l2b-other", "Level 2B: corporate debt A+..BBB- / equities (d238 ¶54(b),(c))"),
    DEPOSIT_AT_FI_OPERATIONAL(
        "deposit-at-fi-operational",
        "Balance at another bank held for clearing / settlement: not HQLA, 0% inflow (d238 ¶156)",
    ),
    DEPOSIT_AT_FI_NON_OPERATIONAL(
        "deposit-at-fi-non-operational",
        "Balance at another bank, withdrawable within 30 days, non-operational: not HQLA, 100% inflow (d238 ¶154)",
    ),
    OTHER_ASSET("other-asset", "Other asset: no LCR inflow, 100% RSF (d295 ¶43(c))"),
    CAPITAL("capital", "Regulatory capital (CET1 / AT1) before deductions (d295 ¶21(a))"),
    CAPITAL_DEDUCTION(
        "capital-deduction",
        "Regulatory deduction: ASF is measured before deductions (d295 ¶17, ¶21(a))",
    ),
    CAPITAL_TIER2("capital-tier2", "Tier 2: 100% ASF only for the part with residual maturity ≥ 1 year (d295 ¶21(a))"),
    OTHER_LIABILITY(
        "other-liability",
        "Other liability without stated maturity: assumed due within 30 days (d238 ¶141), 0% ASF (d295 ¶25(b))",
    ),
    CURRENT_YEAR_RESULT("current-year-result", "Income / expense not yet closed to equity: 0% ASF (d295 ¶25(a))"),
    ;

    val isHqla: Boolean get() = this in HQLA_CLASSES

    companion object {
        private val HQLA_CLASSES =
            setOf(HQLA_L1_CASH_OR_RESERVES, HQLA_L1_SECURITIES, HQLA_L2A, HQLA_L2B_RMBS, HQLA_L2B_OTHER)

        fun parse(raw: String): GlClass =
            entries.firstOrNull { it.wire == raw.trim().lowercase() || it.name == raw.trim() }
                ?: throw IllegalArgumentException(
                    "unknown liquidity class '$raw'; one of ${entries.joinToString { it.wire }}",
                )
    }
}

/**
 * The classification choices the data cannot make on its own. Each one is reported back in the
 * assumptions block, so a figure is never read without the choice behind it.
 *
 *  - [retailStableShare]: share of retail deposits treated as "stable" (d238 ¶75). Deposit
 *    insurance coverage and the depositor relationship are not in the data, so d238 ¶80 applies:
 *    a bank that cannot identify stable deposits places the full amount in "less stable". 0 is
 *    the conservative default.
 *  - [operationalDepositShare]: share of deposits treated as operational (d238 ¶93-104). Needs
 *    supervisory approval and evidence of the clearing / custody / cash-management relationship;
 *    0 unless configured.
 *  - [tier2OverOneYearShare]: share of Tier 2 capital with residual maturity ≥ 1 year (d295
 *    ¶21(a)). Maturities of capital instruments are not in the ledger; 0 is conservative.
 *  - [loansQualifyForLowRiskWeight]: whether loans ≥ 1 year would carry a ≤ 35% standardised risk
 *    weight (d295 ¶41(b), 65%) — risk weights are not in the data, so false (85%, ¶42(b)).
 *  - [glAccounts] / [glAccountTypes]: GL code (or, failing that, GL account type) → class.
 */
data class LiquidityClassification(
    val retailStableShare: BigDecimal,
    val operationalDepositShare: BigDecimal,
    val tier2OverOneYearShare: BigDecimal,
    val loansQualifyForLowRiskWeight: Boolean,
    val glAccounts: Map<String, GlClass>,
    val glAccountTypes: Map<String, GlClass>,
) {
    init {
        requireShare("retail-stable-share", retailStableShare)
        requireShare("operational-deposit-share", operationalDepositShare)
        requireShare("tier2-over-one-year-share", tier2OverOneYearShare)
    }

    fun classOf(glAccountCode: String?, glAccountType: String?): GlClass? =
        glAccountCode?.let { glAccounts[it] } ?: glAccountType?.let { glAccountTypes[it.uppercase()] }
}

/** A versioned, cited parameter set: every result names the [id] and [version] it was computed with. */
data class LiquidityParameters(
    val id: String,
    val version: String,
    val source: String,
    val factors: Map<LiquidityFactor, BigDecimal>,
    val classification: LiquidityClassification,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "liquidity parameter set needs an id and a version" }
        val missing = LiquidityFactor.entries.filter { it !in factors }
        require(missing.isEmpty()) {
            "liquidity parameter set $id v$version is missing factors: ${missing.joinToString { it.key }}"
        }
        factors.forEach { (f, v) -> requireShare(f.key, v) }
    }

    operator fun get(factor: LiquidityFactor): BigDecimal = factors.getValue(factor)

    companion object {
        /** Builds a set from configuration keys; an unknown key is an error, not a silent no-op. */
        fun fromKeys(
            id: String,
            version: String,
            source: String,
            factorsByKey: Map<String, BigDecimal>,
            classification: LiquidityClassification,
        ): LiquidityParameters {
            val unknown = factorsByKey.keys.filter { LiquidityFactor.byKey(it) == null }
            require(unknown.isEmpty()) { "unknown liquidity factor keys: ${unknown.sorted().joinToString()}" }
            val factors = factorsByKey.mapKeys { (k, _) -> LiquidityFactor.byKey(k)!! }
            return LiquidityParameters(id, version, source, factors, classification)
        }
    }
}

private fun requireShare(name: String, value: BigDecimal) =
    require(value.signum() >= 0 && value <= BigDecimal.ONE) { "$name must lie in [0, 1], was $value" }
