// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.annuity

import com.openbank.pension.domain.exit.ExitMoney
import com.openbank.pension.domain.exit.sha256
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One partner's offer, NORMALISED so offers from different insurers compare on the same fields
 * (#12383): the monthly amount, the guarantee, indexation and every fee, and how long it is valid.
 * Whatever an adapter receives, it must map into this shape or drop the offer.
 */
data class AnnuityOffer(
    val offerId: String,
    val partnerId: String,
    val partnerName: String,
    val type: AnnuityType,
    val premium: BigDecimal,
    val currency: String,
    val monthlyAmount: BigDecimal,
    /** Months paid regardless of survival (0 = none). */
    val guaranteeMonths: Int = 0,
    /** FIXED_TERM only: months paid in total. */
    val termMonths: Int? = null,
    /** Yearly increase of the monthly amount (INDEXED), as a fraction; 0 = flat. */
    val indexationRate: BigDecimal = BigDecimal.ZERO,
    /** JOINT_LIFE only: the share the survivor keeps, as a fraction. */
    val survivorShare: BigDecimal? = null,
    /** One-off fee taken from the premium, in money. */
    val oneOffFee: BigDecimal = BigDecimal.ZERO,
    /** Yearly administration fee on the reserve, as a fraction. */
    val annualFeeRate: BigDecimal = BigDecimal.ZERO,
    val validUntil: Instant,
    /** True when the figures are not a binding insurer price (the simulator always says so). */
    val illustrative: Boolean,
    /** The APPROVED registry version of the partner this offer was produced under (pinned, signed). */
    val providerVersion: Int = 0,
) {
    init {
        require(offerId.isNotBlank() && offerId.length <= MAX_REF) { "offerId must be 1..$MAX_REF characters" }
        require(monthlyAmount.signum() > 0) { "an offer pays a positive monthly amount" }
        require(guaranteeMonths >= 0) { "guaranteeMonths must be >= 0" }
        require(type != AnnuityType.FIXED_TERM || (termMonths != null && termMonths > 0)) {
            "a FIXED_TERM offer states termMonths"
        }
        require(type != AnnuityType.JOINT_LIFE || survivorShare != null) { "a JOINT_LIFE offer states survivorShare" }
        require(oneOffFee.signum() >= 0 && annualFeeRate.signum() >= 0 && indexationRate.signum() >= 0) {
            "fees and indexation are never negative"
        }
    }

    /** What the participant is guaranteed to receive in total, whatever happens (comparison field). */
    val guaranteedTotal: BigDecimal
        get() = ExitMoney.round(
            monthlyAmount.multiply(
                BigDecimal(
                    if (type ==
                        AnnuityType.FIXED_TERM
                    ) {
                        termMonths ?: 0
                    } else {
                        guaranteeMonths
                    },
                ),
            ),
        )

    private companion object {
        const val MAX_REF = 128
    }
}

/** A partner that was asked and did not answer usefully — shown, so a missing offer is never silent. */
data class PartnerQuoteFailure(val partnerId: String, val reason: String)

enum class AnnuityPurchaseStatus {
    /** Offers collected; nothing chosen. */
    OFFERED,

    /** The participant chose one offer under SCA. */
    SELECTED,

    /** The partner accepted the application; the premium is not sent yet. */
    APPLIED,

    /** The single premium left for the partner. */
    PREMIUM_SENT,

    /** The partner issued the policy. */
    ACTIVE,

    /** Cancelled by the participant within the partner's cooling-off period. */
    CANCELLED,

    /** Refused or failed; see [AnnuityPurchase.compensation]. */
    FAILED,
}

/** What happened to the money of a purchase that did not end in a policy. */
enum class AnnuityCompensation { NONE, RETURNED_TO_CONTRACT, RETURNED_TO_CLIENT }

/**
 * The annuity purchase of one ANNUITY payout (#12383); its id IS the payout id, so a payout has at
 * most one purchase. The SCA selection binds partner, offer id and amounts ([selectionHash]); the
 * premium is sent only after the partner accepted the application, and a policy is never recorded
 * for a premium that did not leave.
 */
@Suppress("TooManyFunctions") // one function per lifecycle edge
data class AnnuityPurchase(
    val id: UUID,
    val contractId: UUID,
    val participantPartyId: UUID,
    val premium: BigDecimal,
    val currency: String,
    val status: AnnuityPurchaseStatus,
    val offers: List<AnnuityOffer>,
    val failures: List<PartnerQuoteFailure> = emptyList(),
    val quotedAt: Instant,
    val selectedOfferId: String? = null,
    val selectedPartnerId: String? = null,
    val selectionHash: String? = null,
    val scaChallengeId: String? = null,
    val selectedAt: Instant? = null,
    val applicationRef: String? = null,
    /**
     * The approved partner version the application was made under. Every later step (premium,
     * status, cancellation, refund) uses THIS snapshot, so a later edit or disable never redirects
     * or strands money already committed.
     */
    val partner: ApprovedPartner? = null,
    val premiumPaymentRef: String? = null,
    val policyRef: String? = null,
    val policyMonthlyAmount: BigDecimal? = null,
    val policyIssuedOn: LocalDate? = null,
    val coolingOffEndsOn: LocalDate? = null,
    val failureReason: String? = null,
    val compensation: AnnuityCompensation = AnnuityCompensation.NONE,
    val compensationRef: String? = null,
    val updatedAt: Instant,
    val version: Int = 0,
) {
    val selectedOffer: AnnuityOffer?
        get() = offers.firstOrNull { it.offerId == selectedOfferId && it.partnerId == selectedPartnerId }

    fun offer(partnerId: String, offerId: String): AnnuityOffer =
        offers.firstOrNull { it.partnerId == partnerId && it.offerId == offerId }
            ?: throw IllegalArgumentException("offer $partnerId/$offerId is not one of the presented offers")

    /**
     * What the selection's SCA challenge signs: this payout and contract, the PARTNER, the OFFER id,
     * the premium and the monthly amount — so neither another insurer nor another price can be
     * substituted after the participant approved.
     */
    fun selectionHash(offer: AnnuityOffer): String = sha256(
        listOf(
            id, contractId, "annuity-selection", offer.partnerId, offer.offerId,
            premium.toPlainString(), currency, offer.monthlyAmount.toPlainString(), offer.type,
            "provider-v${offer.providerVersion}",
        ).joinToString("|"),
    )

    /** What a cooling-off cancellation's SCA challenge signs. */
    fun cancellationHash(): String = sha256("$id|$contractId|annuity-cancellation|$selectedPartnerId|$policyRef")

    /** New offers replace old ones only while nothing was applied for. */
    fun reoffer(newOffers: List<AnnuityOffer>, newFailures: List<PartnerQuoteFailure>, now: Instant): AnnuityPurchase {
        check(status == AnnuityPurchaseStatus.OFFERED || status == AnnuityPurchaseStatus.SELECTED) {
            "offers cannot change once the purchase is $status"
        }
        return copy(
            status = AnnuityPurchaseStatus.OFFERED,
            offers = newOffers,
            failures = newFailures,
            quotedAt = now,
            selectedOfferId = null,
            selectedPartnerId = null,
            selectionHash = null,
            scaChallengeId = null,
            selectedAt = null,
            updatedAt = now,
        )
    }

    fun select(partnerId: String, offerId: String, scaChallengeId: String, now: Instant): AnnuityPurchase {
        check(status == AnnuityPurchaseStatus.OFFERED || status == AnnuityPurchaseStatus.SELECTED) {
            "an offer can be selected only before the purchase starts, was $status"
        }
        val offer = offer(partnerId, offerId)
        check(now.isBefore(offer.validUntil)) { "the offer expired at ${offer.validUntil}; request new offers" }
        return copy(
            status = AnnuityPurchaseStatus.SELECTED,
            selectedOfferId = offer.offerId,
            selectedPartnerId = offer.partnerId,
            selectionHash = selectionHash(offer),
            scaChallengeId = scaChallengeId,
            selectedAt = now,
            updatedAt = now,
        )
    }

    /** The selection the payout confirmation relies on: still signed, same premium, offer still valid. */
    fun requireBindingSelection(premium: BigDecimal, now: Instant): AnnuityOffer {
        check(status == AnnuityPurchaseStatus.SELECTED) { "select an annuity offer before confirming, was $status" }
        val offer = checkNotNull(selectedOffer) { "the selected offer is missing" }
        check(selectionHash == selectionHash(offer)) { "the selection no longer matches the signed offer" }
        check(this.premium.compareTo(premium) == 0) { "the offer was quoted for another premium" }
        check(now.isBefore(offer.validUntil)) { "the selected offer expired at ${offer.validUntil}" }
        return offer
    }

    fun markApplied(ref: String, under: ApprovedPartner, now: Instant): AnnuityPurchase = when (status) {
        AnnuityPurchaseStatus.APPLIED -> this
        AnnuityPurchaseStatus.SELECTED -> {
            val offer = checkNotNull(selectedOffer)
            check(under.partnerId == offer.partnerId && under.versionNo == offer.providerVersion) {
                "the application must be made under the partner version the offer was quoted under"
            }
            copy(status = AnnuityPurchaseStatus.APPLIED, applicationRef = ref, partner = under, updatedAt = now)
        }
        else -> error("an application is recorded on a SELECTED purchase, was $status")
    }

    fun markPremiumSent(ref: String, now: Instant): AnnuityPurchase = when (status) {
        AnnuityPurchaseStatus.PREMIUM_SENT -> this
        AnnuityPurchaseStatus.APPLIED ->
            copy(status = AnnuityPurchaseStatus.PREMIUM_SENT, premiumPaymentRef = ref, updatedAt = now)
        else -> error("the premium is sent for an APPLIED purchase, was $status")
    }

    /** The policy exists only for a premium that left (the "premium not sent → no policy" invariant). */
    fun markActive(policyRef: String, monthly: BigDecimal, issuedOn: LocalDate, coolingOffDays: Int, now: Instant) =
        when (status) {
            AnnuityPurchaseStatus.ACTIVE -> this
            AnnuityPurchaseStatus.PREMIUM_SENT -> copy(
                status = AnnuityPurchaseStatus.ACTIVE,
                policyRef = policyRef,
                policyMonthlyAmount = ExitMoney.round(monthly),
                policyIssuedOn = issuedOn,
                coolingOffEndsOn = issuedOn.plusDays(coolingOffDays.toLong()),
                updatedAt = now,
            )
            else -> error("a policy can be issued only after the premium was sent, was $status")
        }

    fun fail(reason: String, now: Instant): AnnuityPurchase {
        if (status == AnnuityPurchaseStatus.FAILED) return this
        check(status in FAILABLE) { "a $status purchase cannot fail" }
        return copy(status = AnnuityPurchaseStatus.FAILED, failureReason = reason.take(MAX_REASON), updatedAt = now)
    }

    fun cancelInCoolingOff(today: LocalDate, now: Instant): AnnuityPurchase {
        if (status == AnnuityPurchaseStatus.CANCELLED) return this
        check(status == AnnuityPurchaseStatus.ACTIVE) { "only an ACTIVE policy can be cancelled, was $status" }
        val end = checkNotNull(coolingOffEndsOn)
        check(!today.isAfter(end)) { "the cooling-off period ended on $end" }
        return copy(status = AnnuityPurchaseStatus.CANCELLED, updatedAt = now)
    }

    fun markCompensated(how: AnnuityCompensation, ref: String, now: Instant): AnnuityPurchase {
        check(status == AnnuityPurchaseStatus.FAILED || status == AnnuityPurchaseStatus.CANCELLED) {
            "compensation follows a failure or cancellation, was $status"
        }
        if (compensation != AnnuityCompensation.NONE) return this
        return copy(compensation = how, compensationRef = ref, updatedAt = now)
    }

    companion object {
        private const val MAX_REASON = 500
        private val FAILABLE = setOf(
            AnnuityPurchaseStatus.SELECTED,
            AnnuityPurchaseStatus.APPLIED,
            AnnuityPurchaseStatus.PREMIUM_SENT,
        )

        @Suppress("LongParameterList")
        fun offered(
            payoutId: UUID,
            contractId: UUID,
            participantPartyId: UUID,
            premium: BigDecimal,
            currency: String,
            offers: List<AnnuityOffer>,
            failures: List<PartnerQuoteFailure>,
            now: Instant,
        ) = AnnuityPurchase(
            id = payoutId,
            contractId = contractId,
            participantPartyId = participantPartyId,
            premium = premium,
            currency = currency,
            status = AnnuityPurchaseStatus.OFFERED,
            offers = offers,
            failures = failures,
            quotedAt = now,
            updatedAt = now,
        )
    }
}
