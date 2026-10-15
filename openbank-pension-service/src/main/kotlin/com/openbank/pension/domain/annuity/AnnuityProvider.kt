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

/**
 * The LIVE state of a partner: DRAFT / PENDING_ACTIVATION = never approved (nothing is live);
 * ACTIVE = an approved version is live; DISABLED = no new quotes or purchases.
 */
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
        require(licenceRef.isNotBlank() && licenceAuthority.isNotBlank()) {
            "licenceRef and licenceAuthority are required"
        }
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

/** One approved, immutable version of a partner's terms. */
data class ApprovedTerms(
    val versionNo: Int,
    val terms: AnnuityProviderTerms,
    val editedBy: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
)

/** A proposed version: invisible to the marketplace until a different person approves it. */
data class ProposedTerms(
    val versionNo: Int,
    val terms: AnnuityProviderTerms,
    val editedBy: String,
    val requestedBy: String? = null,
)

/**
 * What the marketplace and every adapter may see of a partner: ONLY an approved version, pinned by
 * its number. A proposal never appears here, so an unapproved edit can never reach a quote or a
 * purchase (#12383 four-eyes).
 */
data class ApprovedPartner(val partnerId: String, val versionNo: Int, val terms: AnnuityProviderTerms)

/**
 * One partner insurer in the registry, VERSIONED under FOUR-EYES (#12383): a maker proposes terms
 * and requests approval; a different checker approves, and only then does that version become the
 * live one. While a proposal is pending the previously approved version stays live, untouched.
 */
@Suppress("TooManyFunctions") // one function per registry edge
data class AnnuityProvider(
    val partnerId: String,
    val status: AnnuityProviderStatus,
    val approved: ApprovedTerms? = null,
    val proposal: ProposedTerms? = null,
    /** Highest version number ever allocated; version numbers are never reused. */
    val lastVersionNo: Int = 0,
    val updatedAt: Instant,
    val version: Int = 0,
) {
    init {
        require(partnerId.matches(PARTNER_ID)) { "partnerId must be 2..40 lower-case letters, digits or '-'" }
        require(approved != null || proposal != null) { "a partner has approved or proposed terms" }
        require(status != AnnuityProviderStatus.ACTIVE || approved != null) { "an ACTIVE partner has approved terms" }
    }

    /** The approved version live at [today], or null: never a proposal, never a disabled partner. */
    fun live(today: LocalDate): ApprovedPartner? {
        val a = approved ?: return null
        if (status != AnnuityProviderStatus.ACTIVE) return null
        if (today.isBefore(a.terms.effectiveFrom)) return null
        if (a.terms.effectiveTo != null && !today.isBefore(a.terms.effectiveTo)) return null
        return ApprovedPartner(partnerId, a.versionNo, a.terms)
    }

    /** Whether the live version may be asked to quote [premium] in [currency] under [jurisdiction] today. */
    fun eligible(jurisdiction: String, currency: String, premium: BigDecimal, today: LocalDate): Boolean {
        val t = live(today)?.terms ?: return false
        return jurisdiction in t.jurisdictions &&
            currency == t.currency &&
            premium >= t.minPremium &&
            premium <= t.maxPremium
    }

    /** A new proposal replaces an unapproved one; the live version is untouched. */
    fun propose(newTerms: AnnuityProviderTerms, by: String, now: Instant): AnnuityProvider {
        require(by.isNotBlank()) { "a proposal needs an author" }
        val no = lastVersionNo + 1
        return copy(
            proposal = ProposedTerms(no, newTerms, by),
            lastVersionNo = no,
            status = if (approved == null) AnnuityProviderStatus.DRAFT else status,
            updatedAt = now,
        )
    }

    fun requestActivation(by: String, now: Instant): AnnuityProvider {
        val p = checkNotNull(proposal) { "no proposed terms to approve; propose terms first" }
        check(p.requestedBy == null) { "approval of version ${p.versionNo} is already requested" }
        return copy(
            proposal = p.copy(requestedBy = by),
            status = if (approved == null) AnnuityProviderStatus.PENDING_ACTIVATION else status,
            updatedAt = now,
        )
    }

    /**
     * The four-eyes check lives HERE, not in a caller: the approver is neither the editor nor the
     * requester of the proposal. The approved version becomes live and the partner ACTIVE.
     */
    fun approveActivation(by: String, now: Instant): AnnuityProvider {
        val p = checkNotNull(proposal) { "no proposal is pending" }
        val requester = checkNotNull(p.requestedBy) { "approval of version ${p.versionNo} was not requested" }
        if (by == requester || by == p.editedBy) {
            throw FourEyesViolationException("the approver must differ from the editor and the requester")
        }
        return copy(
            approved = ApprovedTerms(p.versionNo, p.terms, p.editedBy, requester, by, now),
            proposal = null,
            status = AnnuityProviderStatus.ACTIVE,
            updatedAt = now,
        )
    }

    /** Immediately stops new quotes and purchases; purchases whose premium left continue to settle. */
    fun disable(now: Instant): AnnuityProvider = copy(status = AnnuityProviderStatus.DISABLED, updatedAt = now)

    companion object {
        private val PARTNER_ID = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

        fun draft(partnerId: String, terms: AnnuityProviderTerms, by: String, now: Instant) = AnnuityProvider(
            partnerId,
            AnnuityProviderStatus.DRAFT,
            proposal = ProposedTerms(1, terms, by),
            lastVersionNo = 1,
            updatedAt = now,
        )
    }
}

/** Four-eyes broken: the same person tried to make and check a change. 403. */
class FourEyesViolationException(message: String) : RuntimeException(message)
