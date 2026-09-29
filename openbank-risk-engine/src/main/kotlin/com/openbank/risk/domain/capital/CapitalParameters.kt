// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import java.math.BigDecimal

/** A risk weight (fraction of EAD) or a minimum capital ratio — validated to different ranges. */
enum class FactorKind { RISK_WEIGHT, MINIMUM_RATIO }

/**
 * Every factor the Pillar 1 standardised approach for credit risk applies here, with the provision
 * it comes from under each [CapitalRegime]: [citation] for a BCBS set, [euCitation] for an EU set.
 * The VALUES are not here: they arrive from configuration
 * (`openbank.risk.capital.sa.parameter-sets.<id>.factors`), and [CapitalParameters] refuses a set
 * missing any key or carrying one not listed. So a risk weight is never a code default, and every
 * one has a citation.
 *
 * d424 = BCBS, "Basel III: Finalising post-crisis reforms", December 2017, Part I (standardised
 * approach for credit risk). bcbs189 = BCBS, "Basel III: A global regulatory framework for more
 * resilient banks and banking systems", December 2010 (rev. June 2011). CRR = Regulation (EU)
 * 575/2013 as amended by CRR2 (EU) 2019/876 and CRR3 (EU) 2024/1623, Part Three Title II Chapter 2
 * (risk weights) and Art. 92(1) (own-funds requirements).
 *
 * UNVERIFIED under EU law (the article and the weight are relied on; the PARAGRAPH is not confirmed
 * against the consolidated CRR3 text, and each citation says so — tracked in #11107):
 *  - [RW_BANK_SCRA_GRADE_A], [RW_BANK_SCRA_GRADE_B], [RW_BANK_SCRA_GRADE_C]: CRR3 Art. 121 SCRA
 *    grade weights 40 / 75 / 150 % — which paragraph of the recast Art. 121 carries the table.
 *  - [RW_RETAIL_REGULATORY]: Art. 123 75 % — the paragraph after the CRR3 recast of Art. 123.
 *  - [RW_CORPORATE_UNRATED]: Art. 122 unrated 100 % — the paragraph after the CRR3 recast.
 *  - [RW_CASH], [RW_CASH_ITEMS_IN_COLLECTION]: Art. 134 0 % / 20 % — which of Art. 134(2)-(3)
 *    carries which sentence. Neither is mapped to any GL account today, so no result depends on it.
 *
 * [RW_RETAIL_OTHER] has NO EU counterpart: CRR has no "other retail" class — an exposure that does
 * not meet Art. 123 is a corporate exposure (Art. 122) — so an EU set refuses `other-retail`.
 */
enum class CapitalFactor(val key: String, val kind: FactorKind, val citation: String, val euCitation: String) {
    RW_SOVEREIGN_UNRATED(
        "rw-sovereign-unrated",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶7 Table 1 (sovereigns and central banks, unrated: 100%)",
        "CRR Art. 114(1) (central governments and central banks, no treatment of Art. 114(2)-(7): 100%)",
    ),
    RW_SOVEREIGN_DOMESTIC_CURRENCY(
        "rw-sovereign-domestic-currency",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶8 (national discretion: lower risk weight for the own sovereign / central bank, " +
            "domestic currency, funded in that currency)",
        "CRR Art. 114(4) (Member State central government / central bank, denominated and funded in its domestic currency: 0%)",
    ),
    RW_BANK_SCRA_GRADE_A(
        "rw-bank-scra-grade-a",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶21 Table 7, ¶22-23 (SCRA Grade A base: 40%)",
        "CRR Art. 121 (unrated institution, SCRA Grade A: 40%; paragraph UNVERIFIED)",
    ),
    RW_BANK_SCRA_GRADE_B(
        "rw-bank-scra-grade-b",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶21 Table 7, ¶25-27 (SCRA Grade B base: 75%)",
        "CRR Art. 121 (unrated institution, SCRA Grade B: 75%; paragraph UNVERIFIED)",
    ),
    RW_BANK_SCRA_GRADE_C(
        "rw-bank-scra-grade-c",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶21 Table 7, ¶26, ¶28-29 (SCRA Grade C base: 150%)",
        "CRR Art. 121 (unrated institution, SCRA Grade C: 150%; paragraph UNVERIFIED)",
    ),
    RW_RETAIL_REGULATORY(
        "rw-retail-regulatory",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶55 (regulatory retail meeting all criteria: 75%)",
        "CRR Art. 123 (retail exposure meeting all Art. 123 criteria: 75%; paragraph UNVERIFIED)",
    ),
    RW_RETAIL_OTHER(
        "rw-retail-other",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶57 (other retail: 100%)",
        "No CRR counterpart: an exposure not meeting Art. 123 is a corporate exposure (Art. 122); an EU set " +
            "refuses retail-treatment other-retail, so this value is never applied",
    ),
    RW_CORPORATE_UNRATED(
        "rw-corporate-unrated",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶40 Table 10, ¶41 (unrated corporate: 100%)",
        "CRR Art. 122 (corporate without a credit assessment by a nominated ECAI: 100%; paragraph UNVERIFIED)",
    ),
    RW_DEFAULTED(
        "rw-defaulted",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶90, ¶92 (defaulted, specific provisions < 20% of the outstanding amount: 150%)",
        "CRR Art. 127(1) (exposure in default, specific credit risk adjustments < 20% of the unsecured part: 150%)",
    ),
    RW_CASH(
        "rw-cash",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶96(i) (cash owned and held at the bank or in transit: 0%)",
        "CRR Art. 134 (cash in hand and equivalent cash items: 0%; paragraph UNVERIFIED)",
    ),
    RW_CASH_ITEMS_IN_COLLECTION(
        "rw-cash-items-in-collection",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶97 (cash items in the process of collection: 20%)",
        "CRR Art. 134 (cash items in the process of collection: 20%; paragraph UNVERIFIED)",
    ),
    RW_OTHER_ASSET(
        "rw-other-asset",
        FactorKind.RISK_WEIGHT,
        "BCBS d424 ¶95 (all other assets: 100%)",
        "CRR Art. 134(1) (other items: 100%)",
    ),
    MIN_CET1_RATIO(
        "min-cet1-ratio",
        FactorKind.MINIMUM_RATIO,
        "BCBS bcbs189 ¶50 (CET1 ≥ 4.5% of RWA)",
        "CRR Art. 92(1)(a) (CET1 capital ratio ≥ 4.5%)",
    ),
    MIN_TIER1_RATIO(
        "min-tier1-ratio",
        FactorKind.MINIMUM_RATIO,
        "BCBS bcbs189 ¶50 (Tier 1 ≥ 6.0% of RWA)",
        "CRR Art. 92(1)(b) (Tier 1 capital ratio ≥ 6%)",
    ),
    MIN_TOTAL_CAPITAL_RATIO(
        "min-total-capital-ratio",
        FactorKind.MINIMUM_RATIO,
        "BCBS bcbs189 ¶50 (Total Capital ≥ 8.0% of RWA: the own-funds requirement)",
        "CRR Art. 92(1)(c) (total capital ratio ≥ 8%: the own-funds requirement)",
    ),
    ;

    companion object {
        fun byKey(key: String): CapitalFactor? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What a GL account IS for credit-risk purposes. The trial balance does not say who the
 * counterparty is, so the mapping is configuration (`openbank.risk.capital.sa.classification`);
 * an account it does not name is reported as not classified — never weighted, never dropped.
 */
enum class CapitalGlClass(val wire: String, val description: String) {
    CENTRAL_BANK("central-bank", "Claim on a central bank / sovereign (d424 ¶7-8)"),
    BANK("bank", "Claim on a bank, unrated: SCRA at the configured grade (d424 ¶17(b), ¶21)"),
    RETAIL("retail", "Loan to an individual: the configured retail treatment (d424 ¶54-58)"),
    CASH("cash", "Cash owned and held at the bank or in transit (d424 ¶96(i))"),
    CASH_ITEMS_IN_COLLECTION("cash-items-in-collection", "Cash items in the process of collection (d424 ¶97)"),
    OTHER_ASSET("other-asset", "Other asset (d424 ¶95)"),
    OWN_FUNDS_CET1("own-funds-cet1", "Common Equity Tier 1 element (bcbs189 ¶52)"),
    OWN_FUNDS_CET1_DEDUCTION(
        "own-funds-cet1-deduction",
        "Regulatory adjustment deducted from CET1 (bcbs189 ¶66-89), as booked on this account",
    ),
    OWN_FUNDS_AT1("own-funds-at1", "Additional Tier 1 (bcbs189 ¶54)"),
    OWN_FUNDS_TIER2("own-funds-tier2", "Tier 2 (bcbs189 ¶57)"),
    NOT_AN_EXPOSURE("not-an-exposure", "Liability, non-regulatory equity or P&L: not a credit exposure"),
    ;

    val isOwnFunds: Boolean get() = this in OWN_FUNDS

    companion object {
        private val OWN_FUNDS = setOf(OWN_FUNDS_CET1, OWN_FUNDS_CET1_DEDUCTION, OWN_FUNDS_AT1, OWN_FUNDS_TIER2)

        fun parse(raw: String): CapitalGlClass =
            entries.firstOrNull { it.wire == raw.trim().lowercase() || it.name == raw.trim() }
                ?: throw IllegalArgumentException(
                    "unknown capital GL class '$raw'; one of ${entries.joinToString { it.wire }}",
                )
    }
}

/** How a non-defaulted loan to an individual is weighted: the ¶55 criteria cannot be verified from the data. */
enum class RetailTreatment(val wire: String, val factor: CapitalFactor, val exposureClass: ExposureClass) {
    OTHER_RETAIL("other-retail", CapitalFactor.RW_RETAIL_OTHER, ExposureClass.RETAIL),
    REGULATORY_RETAIL("regulatory-retail", CapitalFactor.RW_RETAIL_REGULATORY, ExposureClass.RETAIL),
    CORPORATE_UNRATED("corporate-unrated", CapitalFactor.RW_CORPORATE_UNRATED, ExposureClass.CORPORATE),
    ;

    companion object {
        fun parse(raw: String): RetailTreatment = entries.firstOrNull { it.wire == raw.trim().lowercase() }
            ?: throw IllegalArgumentException(
                "unknown retail-treatment '$raw'; one of ${entries.joinToString { it.wire }}",
            )
    }
}

/** The SCRA grade (d424 ¶21-29) applied to every bank exposure: no ratings or counterparty data exist. */
enum class ScraGrade(val factor: CapitalFactor) {
    A(CapitalFactor.RW_BANK_SCRA_GRADE_A),
    B(CapitalFactor.RW_BANK_SCRA_GRADE_B),
    C(CapitalFactor.RW_BANK_SCRA_GRADE_C),
    ;

    companion object {
        fun parse(raw: String): ScraGrade = entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw IllegalArgumentException("unknown bank-scra-grade '$raw'; one of A, B, C")
    }
}

/** The reported exposure classes (d424 Part I section A). */
enum class ExposureClass(val wire: String) {
    SOVEREIGN("sovereign-and-central-bank"),
    BANK("bank"),
    RETAIL("retail"),
    CORPORATE("corporate"),
    DEFAULTED("defaulted"),
    CASH("cash"),
    CASH_ITEMS_IN_COLLECTION("cash-items-in-collection"),
    OTHER_ASSET("other-asset"),
}

/**
 * The classification choices the data cannot make on its own; each is reported back in the
 * assumptions block.
 *
 *  - [retailTreatment]: d424 ¶55's product / €1m / 0.2% granularity criteria need obligor-level
 *    aggregation and product data the snapshot lacks, and ¶54 needs the obligor to be a natural
 *    person. Default `other-retail` (¶57, 100%); `corporate-unrated` (¶40-41, 100%) is the reading
 *    for a book that may hold SMEs (¶58) — same weight; `regulatory-retail` (75%) only with evidence.
 *  - [bankScraGrade]: no external ratings and no counterparty capital disclosures are in the data.
 *    ¶23 and ¶26 send a counterparty whose requirements are not made available to Grade C; C
 *    (150%) is the default, B or A only after the ¶22-29 due diligence.
 *  - [domesticCurrency]: the currency in which the d424 ¶8 discretion applies to central-bank
 *    claims; claims on a central bank in any other currency take the unrated ¶7 weight.
 */
data class CapitalClassification(
    val retailTreatment: RetailTreatment,
    val bankScraGrade: ScraGrade,
    val domesticCurrency: String,
    val glAccounts: Map<String, CapitalGlClass>,
    val glAccountTypes: Map<String, CapitalGlClass>,
) {
    init {
        require(domesticCurrency.matches(Regex("[A-Z]{3}"))) {
            "domestic-currency must be an ISO 4217 code, was '$domesticCurrency'"
        }
        val ownFundsByType = glAccountTypes.filterValues { it.isOwnFunds }
        require(ownFundsByType.isEmpty()) {
            "own-funds classes must be mapped per GL code, never by account type: ${ownFundsByType.keys}"
        }
    }

    fun classOf(glAccountCode: String?, glAccountType: String?): CapitalGlClass? =
        glAccountCode?.let { glAccounts[it] } ?: glAccountType?.let { glAccountTypes[it.uppercase()] }
}

/** Which body of rules a parameter set implements, and so which citation each factor carries. */
enum class CapitalRegime(val wire: String, val scope: String) {
    BCBS(
        "bcbs",
        "BCBS d424 SA weights; EU CRR Part Three Title II Chapter 2 not applied. Pillar 1 credit risk only.",
    ),
    EU(
        "eu",
        "EU CRR SA weights: Regulation (EU) 575/2013 as amended by (EU) 2019/876 and (EU) 2024/1623, Part Three " +
            "Title II Chapter 2, and Art. 92(1) minima, for the exposure classes this engine models; citations " +
            "marked UNVERIFIED name the article but not a confirmed paragraph. Pillar 1 credit risk only.",
    ),
    ;

    companion object {
        fun parse(raw: String): CapitalRegime = entries.firstOrNull { it.wire == raw.trim().lowercase() }
            ?: throw IllegalArgumentException(
                "unknown capital regime '$raw'; one of ${entries.joinToString {
                    it.wire
                }}",
            )
    }
}

/** A versioned, cited parameter set: every result names the [id] and [version] it was computed with. */
data class CapitalParameters(
    val id: String,
    val version: String,
    val source: String,
    val factors: Map<CapitalFactor, BigDecimal>,
    val classification: CapitalClassification,
    val regime: CapitalRegime,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "capital parameter set needs an id and a version" }
        require(regime != CapitalRegime.EU || classification.retailTreatment != RetailTreatment.OTHER_RETAIL) {
            "capital parameter set $id is an EU set: retail-treatment 'other-retail' has no CRR counterpart " +
                "(an exposure not meeting Art. 123 is corporate, Art. 122); use corporate-unrated or regulatory-retail"
        }
        val missing = CapitalFactor.entries.filter { it !in factors }
        require(missing.isEmpty()) {
            "capital parameter set $id v$version is missing factors: ${missing.joinToString { it.key }}"
        }
        factors.forEach { (f, v) ->
            when (f.kind) {
                FactorKind.RISK_WEIGHT -> require(v.signum() >= 0 && v <= MAX_RISK_WEIGHT) {
                    "${f.key} must lie in [0, $MAX_RISK_WEIGHT], was $v"
                }
                FactorKind.MINIMUM_RATIO -> require(v.signum() > 0 && v <= BigDecimal.ONE) {
                    "${f.key} must lie in (0, 1], was $v"
                }
            }
        }
    }

    operator fun get(factor: CapitalFactor): BigDecimal = factors.getValue(factor)

    /** The citation for [factor] under this set's [regime]. */
    fun citation(factor: CapitalFactor): String = when (regime) {
        CapitalRegime.BCBS -> factor.citation
        CapitalRegime.EU -> factor.euCitation
    }

    companion object {
        /** 1250%: the highest risk weight the Basel framework assigns; anything above is a typo. */
        val MAX_RISK_WEIGHT = BigDecimal("12.5")

        /** Builds a set from configuration keys; an unknown key is an error, not a silent no-op. */
        fun fromKeys(
            id: String,
            version: String,
            source: String,
            factorsByKey: Map<String, BigDecimal>,
            classification: CapitalClassification,
            regime: CapitalRegime,
        ): CapitalParameters {
            val unknown = factorsByKey.keys.filter { CapitalFactor.byKey(it) == null }
            require(unknown.isEmpty()) { "unknown capital factor keys: ${unknown.sorted().joinToString()}" }
            val factors = factorsByKey.mapKeys { (k, _) -> CapitalFactor.byKey(k)!! }
            return CapitalParameters(id, version, source, factors, classification, regime)
        }
    }
}
