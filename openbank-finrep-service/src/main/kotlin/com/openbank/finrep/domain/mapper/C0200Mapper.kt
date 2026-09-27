// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskCapitalLookup
import com.openbank.finrep.application.port.out.RiskCapitalResult
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps the risk engine's Pillar 1 standardised-approach credit-risk result into COREP C 02.00 Own
 * Funds Requirements (ADR-0313 D6). finrep does NOT compute a single risk weight: the numbers are
 * the risk engine's, from one TIED_OUT snapshot at the report date, so C 02.00 and the admin console
 * cannot disagree.
 *
 * Layout: the post-CRR3 template of Commission Implementing Regulation C(2024) 8389 amending
 * (EU) 2021/451, Annex I sheet CA2 (EBA reporting framework 4.0), column 0010 "TREA". Rows:
 *   0010 TOTAL RISK EXPOSURE AMOUNT                                   — DATA GAP (see below)
 *   0040 RWEA for credit, counterparty credit and dilution risks …    — Σ SA RWEA
 *   0050 Standardised approach (SA)                                   — Σ SA RWEA
 *   0060 SA exposure classes excluding securitisation positions       — Σ SA RWEA
 *   0070 Central governments or central banks                          ← sovereign-and-central-bank
 *   0120 Institutions                                                  ← bank
 *   0125 Corporates - Other                                            ← corporate
 *   0140 Retail                                                        ← retail
 *   0160 Exposures in default                                          ← defaulted
 *   0211 Other items                                                   ← cash, cash-items-in-collection, other-asset
 *   0080-0110, 0131, 0150, 0171-0210                                    — DATA GAP: class not modelled
 *   0520 market risk, 0590 operational risk, 0640 CVA                  — DATA GAP: not computed
 *
 * Nothing is presented as a real zero that the platform cannot stand behind (ADR-0097). Row 0010 is
 * a gap because Article 92(3) TREA includes market, operational and CVA risk, which the engine does
 * not compute: reporting the credit-risk sum there would understate TREA and overstate every ratio.
 * A class the engine does not model is a gap, not a zero, because an exposure of that class could
 * exist and be classified elsewhere. The credit-risk rows are gaps too when the engine left balances
 * unclassified (they carry no RWEA) or the book is multi-currency (no reporting-currency total).
 * Unverified: whether framework 4.2 (the version finrep's XBRL-CSV preflight pins) changed any CA2
 * row code relative to 4.0.
 */
object C0200Mapper {

    const val TEMPLATE_ID = "C_02.00"
    private const val COL = "c0010"

    /** Engine class key → C 02.00 row. An engine class missing here fails the render (never dropped). */
    private val ROW_BY_CLASS: Map<String, String> = mapOf(
        "sovereign-and-central-bank" to "r0070",
        "bank" to "r0120",
        "corporate" to "r0125",
        "retail" to "r0140",
        "defaulted" to "r0160",
        "cash" to "r0211",
        "cash-items-in-collection" to "r0211",
        "other-asset" to "r0211",
    )

    private val MODELLED_ROWS: List<Pair<String, String>> = listOf(
        "r0070" to "Central governments or central banks",
        "r0120" to "Institutions",
        "r0125" to "Corporates - Other",
        "r0140" to "Retail",
        "r0160" to "Exposures in default",
        "r0211" to "Other items",
    )

    private val NOT_MODELLED_ROWS: List<Pair<String, String>> = listOf(
        "r0080" to "Regional governments or local authorities",
        "r0090" to "Public sector entities",
        "r0100" to "Multilateral Development Banks",
        "r0110" to "International Organisations",
        "r0131" to "Corporates - Specialised Lending",
        "r0150" to "Secured by mortgages on immovable property and ADC exposures",
        "r0171" to "Subordinated debt exposures",
        "r0180" to "Covered bonds",
        "r0190" to "Claims on institutions and corporates with a short-term credit assessment",
        "r0200" to "Collective investments undertakings (CIU)",
        "r0210" to "Equity",
    )

    private val NOT_COMPUTED_ROWS: List<Pair<String, String>> = listOf(
        "r0520" to "TOTAL RISK EXPOSURE AMOUNT FOR THE BUSINESS SUBJECT TO MARKET RISK",
        "r0590" to "TOTAL RISK EXPOSURE AMOUNT FOR OPERATIONAL RISK (OpR)",
        "r0640" to "TOTAL RISK EXPOSURE AMOUNT FOR CREDIT VALUATION ADJUSTMENT",
    )

    const val TREA_REASON =
        "Article 92(3) TREA includes market, operational and CVA risk, which the risk engine does not compute; " +
            "the credit-risk sum alone would understate it."
    const val NOT_MODELLED_REASON =
        "The risk engine does not model this exposure class; an exposure of it could exist and be classified elsewhere."
    const val NOT_COMPUTED_REASON = "Not computed by the risk engine (credit risk only, ADR-0313 phase 2)."

    fun map(lookup: RiskCapitalLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val creditGap = lookup.unavailableReason ?: creditGapReason(checkNotNull(result))
        val rwaByRow = if (creditGap == null) rwaByRow(checkNotNull(result)) else emptyMap()
        val sa = rwaByRow.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val currency = result?.currency ?: "CZK"

        fun cell(row: String, label: String, value: BigDecimal?, gap: String?) =
            CorepCell(row, COL, label, value ?: BigDecimal.ZERO, currency, gap != null, gap)

        val cells = buildList {
            add(cell("r0010", "TOTAL RISK EXPOSURE AMOUNT", null, creditGap ?: TREA_REASON))
            add(
                cell(
                    "r0040",
                    "RISK WEIGHTED EXPOSURE AMOUNTS FOR CREDIT, COUNTERPARTY CREDIT AND DILUTION RISKS AND FREE DELIVERIES",
                    sa,
                    creditGap,
                ),
            )
            add(cell("r0050", "Standardised approach (SA)", sa, creditGap))
            add(cell("r0060", "SA exposure classes excluding securitisation positions", sa, creditGap))
            MODELLED_ROWS.forEach { (row, label) -> add(cell(row, label, rwaByRow[row] ?: BigDecimal.ZERO, creditGap)) }
            NOT_MODELLED_ROWS.forEach { (row, label) -> add(cell(row, label, null, NOT_MODELLED_REASON)) }
            NOT_COMPUTED_ROWS.forEach { (row, label) -> add(cell(row, label, null, NOT_COMPUTED_REASON)) }
        }
        return CorepTemplate(TEMPLATE_ID, asOf, cells.sortedBy { it.rowRef })
    }

    private fun creditGapReason(result: RiskCapitalResult): String? = when {
        result.currencyCount > 1 || result.totalRwa == null ->
            "The risk engine's book is multi-currency and it does not convert to one reporting currency, so no " +
                "total can be stated (snapshot ${result.runId})."
        result.unclassifiedBalances > 0 ->
            "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                "they carry no RWEA, so the credit-risk total would be understated."
        else -> null
    }

    private fun rwaByRow(result: RiskCapitalResult): Map<String, BigDecimal> {
        val byRow = result.classes.groupBy { c ->
            ROW_BY_CLASS[c.exposureClass]
                ?: error("risk-engine exposure class '${c.exposureClass}' has no C 02.00 row; add it to C0200Mapper")
        }.mapValues { (_, cs) -> cs.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.rwa) } }
        val total = byRow.values.fold(BigDecimal.ZERO, BigDecimal::add)
        check(total.compareTo(checkNotNull(result.totalRwa)) == 0) {
            "risk-engine classes sum to $total, but its total RWA is ${result.totalRwa} (snapshot ${result.runId})"
        }
        return byRow
    }
}
