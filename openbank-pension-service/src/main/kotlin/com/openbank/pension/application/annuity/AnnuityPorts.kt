// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.annuity

import com.openbank.pension.domain.annuity.AnnuityOffer
import com.openbank.pension.domain.annuity.AnnuityProvider
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.domain.annuity.AnnuityPurchase
import com.openbank.pension.domain.annuity.AnnuityPurchaseStatus
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.ApprovedPartner
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/*
 * Partner-agnostic annuity integration (#12383, ADR-0334). The platform asks EVERY eligible partner
 * the same question and gets back the same normalised answer; how a partner is reached is an
 * adapter, chosen per partner by the registry entry's `adapter` kind.
 */

/** The question put to every eligible partner. [requestId] is the payout id. */
data class AnnuityQuoteRequest(
    val requestId: String,
    val premium: BigDecimal,
    val currency: String,
    val jurisdiction: String,
    val birthDate: LocalDate,
    val startDate: LocalDate,
    /** The types wanted (pack-permitted ∩ participant preference ∩ partner-supported). */
    val types: Set<AnnuityType>,
    val guaranteeMonths: Int? = null,
    val termMonths: Int? = null,
    val jointLifeBirthDate: LocalDate? = null,
    val survivorShare: BigDecimal? = null,
)

/** Apply for the policy of one selected offer. Idempotent on [idempotencyKey]. */
data class AnnuityApplication(
    val requestId: String,
    val offerId: String,
    val premium: BigDecimal,
    val currency: String,
    val holderReference: String,
    val birthDate: LocalDate,
    /** The remittance reference the premium transfer will carry, so the partner can match it. */
    val premiumReference: String,
    val idempotencyKey: String,
)

enum class PartnerPolicyState { APPLIED, PREMIUM_RECEIVED, ACTIVE, REFUSED, CANCELLED }

enum class PartnerCancellationReason { COOLING_OFF, PREMIUM_NOT_SENT }

/** A partner's view of one application/policy. */
data class PartnerPolicyStatus(
    val applicationRef: String,
    val state: PartnerPolicyState,
    val policyRef: String? = null,
    val monthlyAmount: BigDecimal? = null,
    val issuedOn: LocalDate? = null,
    val reason: String? = null,
    /** Set once the partner has returned a received premium (refusal, cooling-off cancellation). */
    val refundRef: String? = null,
)

/**
 * The adapter SPI: one implementation per PROTOCOL, never per insurer. `reference-rest` speaks the
 * published reference protocol (`src/main/resources/annuity-partner-protocol-v1.yaml`); a partner
 * that cannot implement it gets its own adapter class and nothing else changes.
 *
 * An adapter THROWS on transport failure; the caller decides (a quote failure is shown as a
 * partner failure; a purchase-step failure is retried by the workflow).
 */
interface AnnuityProviderAdapter {
    /** The registry `adapter` value this implementation serves. */
    val kind: String

    suspend fun quote(provider: ApprovedPartner, request: AnnuityQuoteRequest): List<AnnuityOffer>

    suspend fun purchase(provider: ApprovedPartner, application: AnnuityApplication): PartnerPolicyStatus

    suspend fun status(provider: ApprovedPartner, applicationRef: String): PartnerPolicyStatus

    suspend fun cancel(
        provider: ApprovedPartner,
        applicationRef: String,
        reason: PartnerCancellationReason,
        idempotencyKey: String,
    ): PartnerPolicyStatus
}

/** The adapters present in THIS build, by kind. A registry entry naming an absent kind cannot be activated. */
interface AnnuityAdapterCatalog {
    fun adapterFor(kind: String): AnnuityProviderAdapter?

    fun kinds(): Set<String>
}

interface AnnuityProviderRepository {
    suspend fun save(provider: AnnuityProvider): AnnuityProvider
    suspend fun find(partnerId: String): AnnuityProvider?
    suspend fun list(status: AnnuityProviderStatus?): List<AnnuityProvider>
}

interface AnnuityPurchaseRepository {
    suspend fun save(purchase: AnnuityPurchase): AnnuityPurchase
    suspend fun findById(id: UUID): AnnuityPurchase?
    suspend fun list(status: AnnuityPurchaseStatus?, limit: Int): List<AnnuityPurchase>
}

/** A registry entry that does not exist. 404. */
class AnnuityNotFoundException(message: String) : RuntimeException(message)

/** A registry write that lost an optimistic-lock race. 409. */
class AnnuityConcurrentUpdateException(message: String) : IllegalStateException(message)
