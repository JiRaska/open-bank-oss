// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.annuity

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** The annuity shapes a partner may offer (#12383). A pack permits a subset; a partner supports a subset. */
enum class AnnuityType {
    /** Paid for life. */
    LIFELONG,

    /** Paid for a fixed number of months, then stops. */
    FIXED_TERM,

    /** Lifelong, but paid at least for a guarantee period (to the estate after an early death). */
    GUARANTEE_PERIOD,

    /** Joint-life / survivor: continues at a survivor share to a second life. */
    JOINT_LIFE,

    /** Lifelong, rising every year by a stated indexation rate. */
    INDEXED,
}

/** Where a premium that did not buy a policy goes (pack rule, #12383). */
enum class RefusedPremiumDestination { CONTRACT, CLIENT }

/** The pack's annuity rules (exit.annuity in the jurisdiction pack JSON). */
data class AnnuityPackRules(
    val permittedTypes: Set<AnnuityType>,
    val refusedPremiumDestination: RefusedPremiumDestination,
    /** A partner offer valid for less than this is not presented: the participant needs time to choose. */
    val quoteValidityMinHours: Int = 0,
) {
    init {
        require(permittedTypes.isNotEmpty()) { "an annuity pack permits at least one annuity type" }
        require(quoteValidityMinHours >= 0) { "quoteValidityMinHours must be >= 0" }
    }
}

enum class AnnuityProviderStatus { DRAFT, PENDING_ACTIVATION, ACTIVE, DISABLED }

/**
 * What the platform knows about a partner insurer — DATA, not code (#12383). Onboarding a new
 * insurer is one registry entry plus, if it does not speak the reference protocol, one adapter.
 *
 * [adapter] names the [com.openbank.pension.application.annuity.AnnuityProviderAdapter] kind that
 * talks to it (`reference-rest` for any partner implementing the published protocol; `simulator`
 * exists only in dev/test builds). [adapterSettings] are NON-SECRET adapter parameters; credentials
 * never live in this registry, they come from configuration keyed by partner id.
 */
data class AnnuityProviderTerms(
    val legalName: String,
    /** The insurer's legal entity in party-service. */
    val legalEntityPartyId: java.util.UUID,
    /** Licence (authorisation) identifier and the supervisor that granted it. */
    val licenceRef: String,
    val licenceAuthority: String,
    /** ISO 3166-1 alpha-2 jurisdictions the insurer may write annuities in. */
    val jurisdictions: Set<String>,
    val supportedTypes: Set<AnnuityType>,
    val currency: String,
    val minPremium: BigDecimal,
    val maxPremium: BigDecimal,
    /** The partner's cooling-off (withdrawal) period after policy issue, in days. */
    val coolingOffDays: Int,
    /** The partner's premium collection account. */
    val premiumIban: String,
    val adapter: String,
    /** Base URL of a reference-protocol partner; null for in-process adapters. */
    val endpointUrl: String? = null,
    val adapterSettings: Map<String, String> = emptyMap(),
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
) {
    init {
        require(legalName.isNotBlank()) { "legalName is required" }
        require(licenceRef.isNotBlank() && licenceAuthority.isNotBlank()) { "licenceRef and licenceAuthority are required" }
        require(jurisdictions.isNotEmpty() && jurisdictions.all { it.matches(COUNTRY) }) {
            "jurisdictions must be ISO 3166-1 alpha-2 codes"
        }
        require(supportedTypes.isNotEmpty()) { "a partner supports at least one annuity type" }
        require(currency.matches(CURRENCY)) { "currency must be an ISO 4217 code" }
        require(minPremium.signum() > 0 && maxPremium >= minPremium) { "0 < minPremium <= maxPremium" }
        require(coolingOffDays in 0..MAX_COOLING_OFF_DAYS) { "coolingOffDays must be 0..$MAX_COOLING_OFF_DAYS" }
        require(premiumIban.matches(IBAN)) { "premiumIban is not a valid IBAN" }
        require(adapter.matches(ADAPTER)) { "adapter must be a lower-case kind such as reference-rest" }
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) { "effectiveTo must be after effectiveFrom" }
    }

    private companion object {
        val COUNTRY = Regex("^[A-Z]{2}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
        val ADAPTER = Regex("^[a-z][a-z0-9-]{1,39}$")
        const val MAX_COOLING_OFF_DAYS = 60
    }
}

/**
 * One partner insurer in the registry, under FOUR-EYES activation: a maker drafts or amends the
 * terms and requests activation; a different checker approves it. Any amendment of an active
 * partner sends it back to DRAFT — changed terms are never live without a second person.
 */
data class AnnuityProvider(
    val partnerId: String,
    val status: AnnuityProviderStatus,
    val terms: AnnuityProviderTerms,
    /** Who last wrote [terms]; may never approve them. */
    val termsEditedBy: String,
    val activationRequestedBy: String? = null,
    val activatedBy: String? = null,
    val activatedAt: Instant? = null,
    val updatedAt: Instant,
    val version: Int = 0,
) {
    init {
        require(partnerId.matches(PARTNER_ID)) { "partnerId must be 2..40 lower-case letters, digits or '-'" }
    }

    /** Whether this partner may be asked to quote [type] (or any of its types when null) for [premium] today. */
    fun eligible(jurisdiction: String, currency: String, premium: BigDecimal, today: LocalDate): Boolean =
        status == AnnuityProviderStatus.ACTIVE &&
            jurisdiction in terms.jurisdictions &&
            currency == terms.currency &&
            premium >= terms.minPremium && premium <= terms.maxPremium &&
            !today.isBefore(terms.effectiveFrom) &&
            (terms.effectiveTo == null || today.isBefore(terms.effectiveTo))

    fun amend(newTerms: AnnuityProviderTerms, by: String, now: Instant): AnnuityProvider {
        require(by.isNotBlank()) { "an amendment needs an author" }
        return copy(
            status = AnnuityProviderStatus.DRAFT,
            terms = newTerms,
            termsEditedBy = by,
            activationRequestedBy = null,
            activatedBy = null,
            activatedAt = null,
            updatedAt = now,
        )
    }

    fun requestActivation(by: String, now: Instant): AnnuityProvider {
        check(status == AnnuityProviderStatus.DRAFT || status == AnnuityProviderStatus.DISABLED) {
            "activation can be requested for a DRAFT or DISABLED partner, was $status"
        }
        return copy(status = AnnuityProviderStatus.PENDING_ACTIVATION, activationRequestedBy = by, updatedAt = now)
    }

    /** The four-eyes check lives HERE, not in a caller: approver != requester and != last editor. */
    fun approveActivation(by: String, now: Instant): AnnuityProvider {
        check(status == AnnuityProviderStatus.PENDING_ACTIVATION) { "no activation is pending, status $status" }
        if (by == activationRequestedBy || by == termsEditedBy) {
            throw FourEyesViolationException("the approver must differ from the editor and the requester")
        }
        return copy(status = AnnuityProviderStatus.ACTIVE, activatedBy = by, activatedAt = now, updatedAt = now)
    }

    fun disable(now: Instant): AnnuityProvider = copy(status = AnnuityProviderStatus.DISABLED, updatedAt = now)

    companion object {
        private val PARTNER_ID = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

        fun draft(partnerId: String, terms: AnnuityProviderTerms, by: String, now: Instant) =
            AnnuityProvider(partnerId, AnnuityProviderStatus.DRAFT, terms, by, updatedAt = now)
    }
}

/** Four-eyes broken: the same person tried to make and check a change. 403. */
class FourEyesViolationException(message: String) : RuntimeException(message)
