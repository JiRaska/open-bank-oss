// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.libs.fincrime.ScreeningPolicy
import java.time.Instant
import java.util.UUID

enum class SanctionsListType {
    OFAC_SDN,
    EU_CONSOLIDATED,
    UN_CONSOLIDATED,
    HM_TREASURY,
    FATF_HIGH_RISK,
    PEP_GLOBAL,
    CNB_DOMESTIC,
    CZ_TERRORISTS,
}
enum class MatchType { EXACT, FUZZY, PHONETIC, ALIAS }

/**
 * What a match MEANS, as opposed to which list it came from. Set only on checks screened with an
 * explicit [ScreeningProfile]; a legacy (profile-less) check carries none and serialises exactly
 * as before.
 *
 * A PEP match is deliberately its own kind: a politically exposed person is a customer-risk
 * fact, never a sanctions designation, and must not block a payment as one.
 */
enum class MatchKind {
    SANCTIONS,
    TERRORIST,
    PEP,
    JURISDICTION,
    ;

    companion object {
        fun ofList(listType: SanctionsListType): MatchKind = when (listType) {
            SanctionsListType.PEP_GLOBAL -> PEP
            SanctionsListType.CZ_TERRORISTS -> TERRORIST
            else -> SANCTIONS
        }
    }
}

/**
 * The explicit purpose of a screen, which fixes the list set and how PEP matches are reported.
 *
 *  - [PAYMENT]: sanctions and national terrorist lists plus jurisdiction reference data. No PEP
 *    list is consulted, so a PEP name cannot stop a payment.
 *  - [CUSTOMER]: the PAYMENT set plus the PEP list. PEP matches are returned with
 *    [MatchKind.PEP] and summarised in [SanctionsCheck.pepStatus]; they never raise
 *    [SanctionsCheck.status].
 *
 * A request without a profile keeps the pre-existing behaviour unchanged.
 */
enum class ScreeningProfile(val lists: List<SanctionsListType>) {
    PAYMENT(
        listOf(
            SanctionsListType.OFAC_SDN,
            SanctionsListType.EU_CONSOLIDATED,
            SanctionsListType.UN_CONSOLIDATED,
            SanctionsListType.HM_TREASURY,
            SanctionsListType.CNB_DOMESTIC,
            SanctionsListType.CZ_TERRORISTS,
            SanctionsListType.FATF_HIGH_RISK,
        ),
    ),
    CUSTOMER(PAYMENT.lists + SanctionsListType.PEP_GLOBAL),
}

/** How a listed jurisdiction is treated: an embargo blocks, a high-risk listing goes to review. */
enum class JurisdictionMeasure { EMBARGO, HIGH_RISK }

/**
 * One active row of jurisdiction reference data. There is no machine-readable feed for these
 * lists, so the rows are versioned reference data delivered by reviewed migrations.
 */
data class SanctionsJurisdiction(
    val listType: SanctionsListType,
    val countryCode: String,
    val measure: JurisdictionMeasure,
    val version: Int,
)

/**
 * sanctions-service's name for the shared check status (ADR-0353 D4), declared once in
 * `openbank-libs-fincrime` so every rail reads the same value set it publishes.
 */
typealias SanctionsCheckStatus = com.openbank.libs.fincrime.ScreeningMatchStatus

enum class EntityType { INDIVIDUAL, ORGANIZATION, VESSEL, AIRCRAFT }

data class SanctionsMatch(
    val listType: SanctionsListType,
    val matchType: MatchType,
    val matchScore: Double, // 0.0 - 1.0
    val matchedName: String,
    val matchedId: String?,
    val listEntryDate: String?,
    val programs: List<String>,
    /** Null on legacy (profile-less) checks, which keep their original wire shape. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val matchKind: MatchKind? = null,
)

data class SanctionsCheck(
    val id: UUID,
    val idempotencyKey: String,
    val entityType: EntityType,
    val name: String,
    val aliases: List<String>,
    val dateOfBirth: String?,
    val nationality: String?,
    val identifiers: Map<String, String>, // passport, taxId, etc.
    val status: SanctionsCheckStatus,
    val matches: List<SanctionsMatch>,
    val overallScore: Double,
    val checkedLists: List<SanctionsListType>,
    val reviewedBy: String?,
    val reviewNote: String?,
    val checkedAt: Instant,
    val reviewedAt: Instant?,
    /** Stable caller-owned identity (for account opening, the party UUID), never parsed from the key. */
    val subjectId: String? = null,
    /** A WHITELISTED review may cover later fresh screens only until this instant. */
    val clearanceExpiresAt: Instant? = null,
    /** The explicit screening purpose; null on legacy checks. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val profile: ScreeningProfile? = null,
    /** ISO 3166-1 alpha-2 country of the screened counterparty, when the caller supplied one. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val country: String? = null,
    /** BIC of the counterparty's bank, when the caller supplied one. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val bankIdentifier: String? = null,
    /** PEP outcome of a CUSTOMER-profile screen, reported apart from [status]; null otherwise. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val pepStatus: SanctionsCheckStatus? = null,
) {
    fun isHighRisk() = status == SanctionsCheckStatus.HIT ||
        (status == SanctionsCheckStatus.POTENTIAL_HIT && overallScore > ScreeningPolicy.POTENTIAL_HIT_BLOCK_THRESHOLD)
}
