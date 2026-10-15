// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity.rest

import com.openbank.pension.application.annuity.AnnuityMarketplaceService
import com.openbank.pension.application.annuity.AnnuityPreferences
import com.openbank.pension.domain.annuity.AnnuityOffer
import com.openbank.pension.domain.annuity.AnnuityProvider
import com.openbank.pension.domain.annuity.AnnuityProviderTerms
import com.openbank.pension.domain.annuity.AnnuityPurchase
import com.openbank.pension.domain.annuity.AnnuityType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AnnuityOffersRequest(
    val annuityTypes: Set<AnnuityType>? = null,
    val guaranteeMonths: Int? = null,
    val termMonths: Int? = null,
    val jointLifeBirthDate: LocalDate? = null,
    val survivorShare: BigDecimal? = null,
) {
    fun toPreferences() =
        AnnuityPreferences(annuityTypes, guaranteeMonths, termMonths, jointLifeBirthDate, survivorShare)
}

data class AnnuitySelectionRequest(
    val partnerId: String? = null,
    val offerId: String? = null,
    val scaChallengeId: String? = null,
)

data class AnnuityCancellationRequest(val scaChallengeId: String? = null)

data class AnnuityOfferResponse(
    val partnerId: String,
    val partnerName: String,
    val offerId: String,
    val annuityType: AnnuityType,
    val premium: BigDecimal,
    val currency: String,
    val monthlyAmount: BigDecimal,
    val guaranteeMonths: Int,
    val termMonths: Int?,
    val indexationRate: BigDecimal,
    val survivorShare: BigDecimal?,
    val oneOffFee: BigDecimal,
    val annualFeeRate: BigDecimal,
    val guaranteedTotal: BigDecimal,
    val validUntil: Instant,
    val illustrative: Boolean,
    val providerVersion: Int,
    /** What an SCA challenge selecting THIS offer must be signed over. */
    val selectionHash: String,
) {
    companion object {
        fun from(o: AnnuityOffer, purchase: AnnuityPurchase) = AnnuityOfferResponse(
            o.partnerId, o.partnerName, o.offerId, o.type, o.premium, o.currency, o.monthlyAmount, o.guaranteeMonths,
            o.termMonths, o.indexationRate, o.survivorShare, o.oneOffFee, o.annualFeeRate, o.guaranteedTotal,
            o.validUntil, o.illustrative, o.providerVersion, purchase.selectionHash(o),
        )
    }
}

data class PartnerFailureResponse(val partnerId: String, val reason: String)

data class AnnuityPurchaseResponse(
    val payoutId: UUID,
    val contractId: UUID,
    val status: String,
    val premium: BigDecimal,
    val currency: String,
    val quotedAt: Instant,
    /** The disclosed, partner-neutral order the offers are listed in (no steering). */
    val presentationOrder: String,
    val offers: List<AnnuityOfferResponse>,
    val partnerFailures: List<PartnerFailureResponse>,
    val selectedPartnerId: String?,
    val selectedOfferId: String?,
    val applicationRef: String?,
    val premiumPaymentRef: String?,
    val policyRef: String?,
    val policyMonthlyAmount: BigDecimal?,
    val coolingOffEndsOn: LocalDate?,
    /** What an SCA challenge cancelling the issued policy must be signed over (null before issue). */
    val cancellationHash: String?,
    val failureReason: String?,
    val compensation: String,
    val disclaimer: String,
) {
    companion object {
        const val DISCLAIMER =
            "Offers come from independent insurers and are listed in a fixed order disclosed above; the platform " +
                "recommends none of them. Offers marked illustrative are not a binding price."

        fun from(p: AnnuityPurchase) = AnnuityPurchaseResponse(
            p.id, p.contractId, p.status.name, p.premium, p.currency, p.quotedAt,
            AnnuityMarketplaceService.PRESENTATION_ORDER,
            p.offers.map { AnnuityOfferResponse.from(it, p) },
            p.failures.map { PartnerFailureResponse(it.partnerId, it.reason) },
            p.selectedPartnerId, p.selectedOfferId, p.applicationRef, p.premiumPaymentRef, p.policyRef,
            p.policyMonthlyAmount, p.coolingOffEndsOn,
            if (p.policyRef != null) p.cancellationHash() else null,
            p.failureReason, p.compensation.name, DISCLAIMER,
        )
    }
}

/** Registry terms on the wire; every field validated by [AnnuityProviderTerms]. */
data class AnnuityProviderTermsRequest(
    val legalName: String? = null,
    val legalEntityPartyId: UUID? = null,
    val licenceRef: String? = null,
    val licenceAuthority: String? = null,
    val jurisdictions: Set<String>? = null,
    val supportedTypes: Set<AnnuityType>? = null,
    val currency: String? = null,
    val minPremium: BigDecimal? = null,
    val maxPremium: BigDecimal? = null,
    val coolingOffDays: Int? = null,
    val premiumIban: String? = null,
    val adapter: String? = null,
    val endpointUrl: String? = null,
    val adapterSettings: Map<String, String>? = null,
    val effectiveFrom: LocalDate? = null,
    val effectiveTo: LocalDate? = null,
) {
    fun toTerms() = AnnuityProviderTerms(
        legalName = requireNotNull(legalName) { "legalName is required" },
        legalEntityPartyId = requireNotNull(legalEntityPartyId) { "legalEntityPartyId is required" },
        licenceRef = requireNotNull(licenceRef) { "licenceRef is required" },
        licenceAuthority = requireNotNull(licenceAuthority) { "licenceAuthority is required" },
        jurisdictions = requireNotNull(jurisdictions) { "jurisdictions is required" },
        supportedTypes = requireNotNull(supportedTypes) { "supportedTypes is required" },
        currency = requireNotNull(currency) { "currency is required" },
        minPremium = requireNotNull(minPremium) { "minPremium is required" },
        maxPremium = requireNotNull(maxPremium) { "maxPremium is required" },
        coolingOffDays = requireNotNull(coolingOffDays) { "coolingOffDays is required" },
        premiumIban = requireNotNull(premiumIban) { "premiumIban is required" },
        adapter = requireNotNull(adapter) { "adapter is required" },
        endpointUrl = endpointUrl,
        adapterSettings = adapterSettings.orEmpty(),
        effectiveFrom = requireNotNull(effectiveFrom) { "effectiveFrom is required" },
        effectiveTo = effectiveTo,
    )
}

data class CreateAnnuityProviderRequest(val partnerId: String? = null, val terms: AnnuityProviderTermsRequest? = null)

/** The live approved version and any pending proposal, side by side (the checker reviews the diff). */
data class AnnuityProviderResponse(
    val partnerId: String,
    val status: String,
    val liveVersion: Int?,
    val liveTerms: AnnuityProviderTerms?,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val proposedVersion: Int?,
    val proposedTerms: AnnuityProviderTerms?,
    val proposedBy: String?,
    val activationRequestedBy: String?,
    val updatedAt: Instant,
) {
    companion object {
        fun from(p: AnnuityProvider) = AnnuityProviderResponse(
            p.partnerId, p.status.name, p.approved?.versionNo, p.approved?.terms, p.approved?.approvedBy,
            p.approved?.approvedAt, p.proposal?.versionNo, p.proposal?.terms, p.proposal?.editedBy,
            p.proposal?.requestedBy, p.updatedAt,
        )
    }
}
