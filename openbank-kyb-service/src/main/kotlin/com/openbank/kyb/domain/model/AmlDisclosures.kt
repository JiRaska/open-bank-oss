// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.Instant
import java.util.UUID

/** Why the entity wants the relationship (AML Act 253/2008 §9(2)(c): purpose and intended nature). */
enum class RelationshipPurpose { OPERATING_ACCOUNT, SAVINGS, PAYMENTS_ESHOP, HOLDING, OTHER }

/** Expected monthly turnover in CZK, as a band — the customer is estimating, not reporting. */
enum class ExpectedTurnover { UP_TO_100K, UP_TO_1M, UP_TO_10M, OVER_10M }

enum class SourceOfFunds { BUSINESS_REVENUE, SHARE_CAPITAL, LOANS, INVESTMENTS, OTHER }

/** FATCA entity classification. Only [ACTIVE_NFFE] is the plain, non-US operating-company answer. */
enum class FatcaStatus { ACTIVE_NFFE, PASSIVE_NFFE, FINANCIAL_INSTITUTION, US_PERSON }

/** CRS entity classification. */
enum class CrsStatus { ACTIVE_NFE, PASSIVE_NFE, FINANCIAL_INSTITUTION }

/**
 * The AML / FATCA / CRS questionnaire. Stored as the customer answered it; [validated] is the one
 * place the answers are checked, and it throws [IllegalArgumentException] (a 400) for anything a
 * well-behaved client could not have sent.
 */
data class Questionnaire(
    val purpose: RelationshipPurpose,
    val purposeNote: String? = null,
    val expectedMonthlyTurnover: ExpectedTurnover,
    val sourceOfFunds: Set<SourceOfFunds>,
    val sourceOfFundsNote: String? = null,
    val cashIntensive: Boolean,
    /** ISO-3166 alpha-2 codes of the countries the entity does business with / in. */
    val countries: List<String>,
    /** ISO-3166 alpha-2 codes of the entity's tax residencies. */
    val taxResidencies: List<String>,
    val fatcaStatus: FatcaStatus,
    val crsStatus: CrsStatus,
    val answeredAt: Instant? = null,
    val answeredBy: UUID? = null,
) {
    fun validated(): Questionnaire {
        require(purpose != RelationshipPurpose.OTHER || !purposeNote.isNullOrBlank()) {
            "purposeNote is required when purpose is OTHER"
        }
        require(sourceOfFunds.isNotEmpty()) { "at least one sourceOfFunds is required" }
        require(SourceOfFunds.OTHER !in sourceOfFunds || !sourceOfFundsNote.isNullOrBlank()) {
            "sourceOfFundsNote is required when sourceOfFunds includes OTHER"
        }
        require(countries.isNotEmpty()) { "at least one country is required" }
        require(taxResidencies.isNotEmpty()) { "at least one tax residency is required" }
        (countries + taxResidencies).forEach {
            require(ALPHA2.matches(it)) { "'$it' is not an ISO-3166 alpha-2 country code" }
        }
        listOfNotNull(purposeNote, sourceOfFundsNote).forEach {
            require(it.length <= MAX_NOTE) { "notes are limited to $MAX_NOTE characters" }
        }
        return copy(countries = countries.distinct(), taxResidencies = taxResidencies.distinct())
    }

    companion object {
        val ALPHA2 = Regex("^[A-Z]{2}$")
        const val MAX_NOTE = 500
    }
}

/**
 * One person's politically-exposed-person status. [partyId] is set when the value was taken from
 * the person's own customer profile rather than declared in this case.
 */
data class PepEntry(val name: String, val isPep: Boolean, val detail: String? = null, val partyId: UUID? = null)

/**
 * A person on the case the bank already knows as a customer (the initiator, an identified
 * co-signer). [pep] null means the profile holds no PEP fact, so the person still has to declare.
 */
data class KnownPerson(val name: String, val partyId: UUID, val pep: Boolean?, val pepCategory: String?)

/**
 * The customer's declarations: the register's beneficial owners are right (or a discrepancy is
 * reported — AML Act §9(2)(b) and the UBO register act oblige the bank to report it), who among the
 * owners and representatives is a PEP, and that the whole is truthful.
 */
data class Declarations(
    val uboConfirmed: Boolean,
    val uboDiscrepancyNote: String? = null,
    val peps: List<PepEntry>,
    val truthful: Boolean,
    /**
     * Names whose declared PEP value contradicted their customer profile. The profile value is
     * kept; the contradiction itself is a review flag.
     */
    val profileConflicts: List<String> = emptyList(),
    val declaredAt: Instant? = null,
    val declaredBy: UUID? = null,
) {
    /**
     * [requiredPersons] are the names the declaration must cover — every reportable UBO and every
     * listed representative. A person in [known] whose profile carries a PEP fact is covered by the
     * profile and need not be declared; if the request declares them anyway and disagrees, the
     * profile wins and the disagreement is recorded. Matching ignores titles, diacritics and word
     * order, so none of those makes a person "missing".
     */
    fun validated(requiredPersons: List<String>, known: List<KnownPerson> = emptyList()): Declarations {
        require(truthful) { "the truthfulness declaration must be confirmed" }
        require(uboConfirmed || !uboDiscrepancyNote.isNullOrBlank()) {
            "uboDiscrepancyNote is required when the beneficial owners are not confirmed"
        }
        uboDiscrepancyNote?.let { require(it.length <= Questionnaire.MAX_NOTE) { "uboDiscrepancyNote is too long" } }
        peps.forEach {
            require(it.name.isNotBlank()) { "every PEP entry needs a name" }
            require(!it.isPep || !it.detail.isNullOrBlank()) { "a PEP entry for ${it.name} needs a detail (function)" }
        }
        val onFile = known.filter { it.pep != null }
        val fromProfile = onFile.map { PepEntry(it.name, it.pep!!, it.pepCategory, it.partyId) }
        val declared = peps.filter { e -> onFile.none { samePerson(it.name, e.name) } }
        val conflicts = peps.filter { e -> onFile.any { samePerson(it.name, e.name) && it.pep != e.isPep } }
            .map { it.name }
        val effective = fromProfile + declared.map { it.copy(partyId = null) }
        val missing = requiredPersons.distinct().filter { person -> effective.none { samePerson(it.name, person) } }
        require(missing.isEmpty()) { "PEP declaration missing for: ${missing.joinToString(", ")}" }
        return copy(peps = effective, profileConflicts = conflicts)
    }

    private fun samePerson(a: String, b: String): Boolean =
        IdentityMatch.tokens(a).sorted() == IdentityMatch.tokens(b).sorted()
}

/** A disclosure document the customer ticked as read, bound to its exact bytes. */
data class AcceptedDisclosure(val code: String, val version: String, val sha256: String)

/** One signer's acceptance of the disclosure set. */
data class DisclosureAcceptance(val partyId: UUID, val acceptedAt: Instant)

/**
 * The framework agreement document-service rendered for this case and the ceremony that signs it.
 * [acceptedDisclosures]/[acceptedAt]/[acceptedBy] describe the latest acceptance; [acceptances]
 * records every signer who accepted, because each signer — not just the first — must have seen the
 * annexes before signing.
 */
data class AgreementRecord(
    val documentId: UUID,
    val ceremonyId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val lang: String,
    val acceptedDisclosures: List<AcceptedDisclosure> = emptyList(),
    val acceptedAt: Instant? = null,
    val acceptedBy: UUID? = null,
    val acceptances: List<DisclosureAcceptance> = emptyList(),
) {
    fun acceptedByParty(partyId: UUID): Boolean = acceptances.any { it.partyId == partyId }

    /** Same document and ceremony — a re-render that changed neither keeps the acceptances. */
    fun sameDocumentAs(other: AgreementRecord): Boolean =
        documentId == other.documentId && ceremonyId == other.ceremonyId && sha256 == other.sha256
}

/**
 * A signing/acceptance precondition is not met. 409 with [code], so a client can tell "accept the
 * annexes first" from "that is not your ceremony".
 */
class AgreementConflictException(val code: String, message: String) : RuntimeException(message) {
    companion object {
        const val PREREQUISITES_MISSING = "AGREEMENT_PREREQUISITES_MISSING"
        const val NOT_PREPARED = "AGREEMENT_NOT_PREPARED"
        const val DISCLOSURES_STALE = "DISCLOSURES_STALE"
        const val DISCLOSURES_NOT_ACCEPTED = "DISCLOSURES_NOT_ACCEPTED"
        const val SIGNATURE_REF_MISMATCH = "SIGNATURE_REF_MISMATCH"
        const val CEREMONY_NOT_FOUND = "CEREMONY_NOT_FOUND"
        const val CEREMONY_CASE_MISMATCH = "CEREMONY_CASE_MISMATCH"
        const val CEREMONY_NOT_SIGNED = "CEREMONY_NOT_SIGNED"
        const val AGREEMENT_LOCKED = "AGREEMENT_LOCKED"
    }
}

/**
 * The risk flags that stop an automatic activation (never silently): each is a reason a human
 * reviewer must see. [homeCountry] is the entity's own jurisdiction — a tax residency anywhere
 * else is a flag; for a Czech entity that is "outside CZ".
 */
object AmlRiskFlags {
    fun of(q: Questionnaire?, d: Declarations?, homeCountry: String?, highRiskCountries: Set<String>): List<String> {
        val flags = mutableListOf<String>()
        if (d != null) {
            val peps = d.peps.filter { it.isPep }
            if (peps.isNotEmpty()) flags += "PEP declared: ${peps.joinToString(", ") { "${it.name} (${it.detail})" }}"
            if (!d.uboConfirmed) flags += "beneficial-owner discrepancy reported: ${d.uboDiscrepancyNote}"
            if (d.profileConflicts.isNotEmpty()) {
                flags += "PEP declaration differs from customer profile: ${d.profileConflicts.joinToString(", ")}"
            }
        }
        if (q != null) {
            if (q.fatcaStatus != FatcaStatus.ACTIVE_NFFE) flags += "FATCA status ${q.fatcaStatus}"
            val foreignTax = q.taxResidencies.filter { homeCountry == null || it != homeCountry.uppercase() }
            if (foreignTax.isNotEmpty()) flags += "tax residency outside $homeCountry: ${foreignTax.joinToString(", ")}"
            if (q.cashIntensive) flags += "cash-intensive business"
            val risky = q.countries.filter { it in highRiskCountries }
            if (risky.isNotEmpty()) flags += "high-risk countries: ${risky.joinToString(", ")}"
        }
        return flags
    }
}
