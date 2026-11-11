// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.SanctionsJurisdictionRepository
import com.openbank.sanctions.domain.model.JurisdictionMeasure
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.MatchType
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsMatch
import com.openbank.sanctions.domain.model.ScreeningProfile
import jakarta.enterprise.context.ApplicationScoped

/**
 * The screening inputs beyond the name (issue #15723): the explicit profile, the counterparty
 * country and bank identifier, and how their matches combine with name matches into an outcome.
 * Kept apart from [SanctionsService] so the legacy, profile-less path there stays readable.
 */
@ApplicationScoped
class ScreeningProfileEvaluator(private val jurisdictionRepo: SanctionsJurisdictionRepository) {

    fun statusOf(matches: List<SanctionsMatch>): SanctionsCheckStatus = when {
        matches.any { it.matchScore >= HIT_THRESHOLD } -> SanctionsCheckStatus.HIT
        matches.any { it.matchScore >= POTENTIAL_HIT_THRESHOLD } -> SanctionsCheckStatus.POTENTIAL_HIT
        else -> SanctionsCheckStatus.CLEAR
    }

    fun parseProfile(raw: String?): ScreeningProfile? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        return ScreeningProfile.entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException(
                "unknown screening profile '$value'; expected one of ${ScreeningProfile.entries.joinToString(", ")}",
            )
    }

    fun parseCountry(raw: String?): String? {
        val value = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        require(COUNTRY.matches(value)) { "country must be an ISO 3166-1 alpha-2 code" }
        return value
    }

    fun parseBankIdentifier(raw: String?): String? {
        val value = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        require(BIC.matches(value)) { "bankIdentifier must be a BIC of 8 or 11 characters" }
        return value
    }

    /**
     * Match the counterparty's country, and the country embedded in its bank's BIC, against the
     * jurisdiction reference data. A listed country matches whatever the name is: an embargo is a
     * HIT, a high-risk listing a POTENTIAL_HIT scored at the review floor so the shared
     * `ScreeningPolicy` routes it to review instead of blocking it.
     */
    suspend fun screenJurisdictions(
        country: String?,
        bankIdentifier: String?,
        targetLists: List<SanctionsListType>,
    ): List<SanctionsMatch> {
        val bankCountry = bankIdentifier?.substring(BIC_COUNTRY_START, BIC_COUNTRY_END)
        val countries = setOfNotNull(country, bankCountry)
        return jurisdictionRepo.findActive(countries, targetLists).map { row ->
            SanctionsMatch(
                listType = row.listType,
                matchType = MatchType.EXACT,
                matchScore = when (row.measure) {
                    JurisdictionMeasure.EMBARGO -> 1.0
                    JurisdictionMeasure.HIGH_RISK -> POTENTIAL_HIT_THRESHOLD
                },
                matchedName = row.countryCode,
                matchedId = "country:${row.countryCode}",
                listEntryDate = null,
                programs = listOf("${row.measure.name}:v${row.version}"),
                matchKind = MatchKind.JURISDICTION,
            )
        }
    }

    class Outcome(
        val status: SanctionsCheckStatus,
        val pepStatus: SanctionsCheckStatus?,
        val matches: List<SanctionsMatch>,
        val overallScore: Double,
    )

    /**
     * Combine name and jurisdiction matches into the check's outcome.
     *
     * Without a profile this is the legacy rule, untouched: every name match counts toward the
     * status, PEP included. With a profile, PEP matches are set apart — they are reported with
     * their own kind and summarised in `pepStatus`, and neither the status nor the score that
     * callers feed into their block/review decision sees them.
     */
    fun combine(
        profile: ScreeningProfile?,
        nameStatus: SanctionsCheckStatus,
        nameMatches: List<SanctionsMatch>,
        jurisdictionMatches: List<SanctionsMatch>,
    ): Outcome {
        if (profile == null && jurisdictionMatches.isEmpty()) {
            return Outcome(nameStatus, null, nameMatches, nameMatches.maxOfOrNull { it.matchScore } ?: 0.0)
        }
        val named = if (profile == null) {
            nameMatches
        } else {
            nameMatches.map { it.copy(matchKind = MatchKind.ofList(it.listType)) }
        }
        val (pep, deciding) = named.partition { it.matchKind == MatchKind.PEP }
        val decidingAll = jurisdictionMatches + deciding
        return Outcome(
            status = statusOf(decidingAll),
            pepStatus = if (profile == ScreeningProfile.CUSTOMER) statusOf(pep) else null,
            matches = (decidingAll + pep).sortedByDescending { it.matchScore },
            overallScore = decidingAll.maxOfOrNull { it.matchScore } ?: 0.0,
        )
    }

    companion object {
        const val HIT_THRESHOLD = 0.85
        const val POTENTIAL_HIT_THRESHOLD = 0.65
        private const val BIC_COUNTRY_START = 4
        private const val BIC_COUNTRY_END = 6
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val BIC = Regex("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$")
    }
}
