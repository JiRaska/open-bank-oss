// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class AccountPurpose { EVERYDAY_BANKING, SALARY, SAVINGS, INVESTMENTS, BUSINESS_SIDE_INCOME, OTHER }

enum class IncomeSource {
    EMPLOYMENT,
    SELF_EMPLOYMENT,
    PENSION,
    STUDENT_SUPPORT,
    RENTAL,
    INVESTMENTS,
    INHERITANCE_GIFT,
    OTHER,
}

enum class Occupation { EMPLOYED, SELF_EMPLOYED, STUDENT, RETIRED, UNEMPLOYED, OTHER }

/** Expected monthly turnover in CZK, as a band — the declaration never asks for an exact amount. */
enum class ExpectedMonthlyTurnover { UP_TO_30K, UP_TO_100K, UP_TO_500K, OVER_500K }

enum class PepCategory {
    HEAD_OF_STATE_OR_GOV,
    MINISTER,
    MP,
    JUDGE,
    CENTRAL_BANK,
    AMBASSADOR,
    MILITARY,
    STATE_COMPANY_BOARD,
    INTL_ORG,
    PARTY_LEADERSHIP,
    FAMILY_MEMBER,
    CLOSE_ASSOCIATE,
}

/** `parties.fatca_status` vocabulary. V2 created the column with no values; these are the first. */
enum class FatcaStatus { US_PERSON, NON_US }

/** `parties.crs_status` vocabulary. REPORTABLE = at least one tax residency outside CZ. */
enum class CrsStatus { REPORTABLE, NON_REPORTABLE }

/** Why a declaration routes the party to enhanced due diligence. Order is the declaration order. */
enum class AmlRiskFactor { PEP, US_PERSON, CASH_INTENSIVE, HIGH_RISK_COUNTRY, HIGH_TURNOVER }

data class PepDeclaration(val isPep: Boolean, val category: PepCategory?, val detail: String?)

/** One tax residency; [tin] is only meaningful (and only accepted) for a non-CZ residency. */
data class TaxResidency(val country: String, val tin: String?)

class InvalidAmlProfileException(message: String) : IllegalArgumentException(message)

/** A person-only declaration was attempted for a legal entity or a retired party (mapped to 422). */
class AmlProfileNotApplicableException(message: String) : RuntimeException(message)

/**
 * What the customer declares, before the service stamps version/time on it. [validate] is the
 * whole rule set; a declaration that passes it is a legal [PartyAmlProfile] content.
 */
data class AmlProfileDeclaration(
    val purposes: Set<AccountPurpose>,
    val purposeNote: String?,
    val incomeSources: Set<IncomeSource>,
    val incomeNote: String?,
    val occupation: Occupation,
    val occupationNote: String?,
    val expectedMonthlyTurnover: ExpectedMonthlyTurnover,
    val cashIntensive: Boolean,
    val pep: PepDeclaration,
    val taxResidencies: List<TaxResidency>,
    val usPerson: Boolean,
    val truthful: Boolean,
) {
    fun validate() {
        check(purposes.isNotEmpty()) { "purpose must name at least one value" }
        check(AccountPurpose.OTHER !in purposes || !purposeNote.isNullOrBlank()) {
            "purposeNote is required when purpose includes OTHER"
        }
        check(incomeSources.isNotEmpty()) { "incomeSources must name at least one value" }
        check(IncomeSource.OTHER !in incomeSources || !incomeNote.isNullOrBlank()) {
            "incomeNote is required when incomeSources includes OTHER"
        }
        check(occupation != Occupation.OTHER || !occupationNote.isNullOrBlank()) {
            "occupationNote is required when occupation is OTHER"
        }
        check(!pep.isPep || pep.category != null) { "pep.category is required when pep.isPep is true" }
        check(pep.isPep || (pep.category == null && pep.detail.isNullOrBlank())) {
            "pep.category and pep.detail must be empty when pep.isPep is false"
        }
        validateResidencies()
        listOf(
            "purposeNote" to purposeNote,
            "incomeNote" to incomeNote,
            "occupationNote" to occupationNote,
            "pep.detail" to pep.detail,
        ).forEach { (field, value) ->
            check((value?.length ?: 0) <= MAX_NOTE) { "$field exceeds $MAX_NOTE characters" }
        }
        check(truthful) { "truthful must be true — the declaration cannot be stored without it" }
    }

    private fun validateResidencies() {
        check(taxResidencies.isNotEmpty()) { "taxResidencies must name at least one country" }
        taxResidencies.forEach { r ->
            check(r.country in ISO_COUNTRIES) { "taxResidencies: '${r.country}' is not an ISO 3166-1 alpha-2 code" }
            check(r.tin == null || r.country != DOMESTIC) { "tin is only accepted for a non-CZ tax residency" }
            check((r.tin?.length ?: 0) <= MAX_TIN) { "tin for ${r.country} exceeds $MAX_TIN characters" }
        }
        check(taxResidencies.map { it.country }.toSet().size == taxResidencies.size) {
            "taxResidencies must not repeat a country"
        }
        // A US tax resident is a US person for FATCA by definition; a declaration saying
        // otherwise is self-contradictory and would derive NON_US for a reportable person.
        check(usPerson || taxResidencies.none { it.country == US }) {
            "usPerson must be true when US is a tax residency"
        }
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidAmlProfileException(message())
    }

    companion object {
        const val DOMESTIC = "CZ"
        const val US = "US"
        const val MAX_NOTE = 500
        const val MAX_TIN = 64
        private val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toSet()
    }
}

/** The four facts other services read off `parties` (kyb reads them for business onboarding). */
data class AmlDerivedFacts(
    val pepFlag: Boolean,
    val pepCategory: PepCategory?,
    val fatcaStatus: FatcaStatus,
    val crsStatus: CrsStatus,
)

data class PartyAmlProfile(
    val partyId: UUID,
    val version: Int,
    val declaration: AmlProfileDeclaration,
    val riskFactors: List<AmlRiskFactor>,
    val declaredAt: Instant,
    val declaredBy: String,
) {
    /** Any risk factor routes the party to enhanced due diligence — never a silent block. */
    val eddRequired: Boolean get() = riskFactors.isNotEmpty()

    fun derivedFacts(): AmlDerivedFacts = AmlProfiles.derive(declaration)
}

object AmlProfiles {

    fun derive(d: AmlProfileDeclaration): AmlDerivedFacts = AmlDerivedFacts(
        pepFlag = d.pep.isPep,
        pepCategory = d.pep.category.takeIf { d.pep.isPep },
        fatcaStatus = if (d.usPerson) FatcaStatus.US_PERSON else FatcaStatus.NON_US,
        crsStatus = if (d.taxResidencies.any { it.country != AmlProfileDeclaration.DOMESTIC }) {
            CrsStatus.REPORTABLE
        } else {
            CrsStatus.NON_REPORTABLE
        },
    )

    fun riskFactors(d: AmlProfileDeclaration, highRiskCountries: Set<String>): List<AmlRiskFactor> = buildList {
        if (d.pep.isPep) add(AmlRiskFactor.PEP)
        if (d.usPerson) add(AmlRiskFactor.US_PERSON)
        if (d.cashIntensive) add(AmlRiskFactor.CASH_INTENSIVE)
        if (d.taxResidencies.any { it.country in highRiskCountries }) add(AmlRiskFactor.HIGH_RISK_COUNTRY)
        if (d.expectedMonthlyTurnover == ExpectedMonthlyTurnover.OVER_500K) add(AmlRiskFactor.HIGH_TURNOVER)
    }
}
