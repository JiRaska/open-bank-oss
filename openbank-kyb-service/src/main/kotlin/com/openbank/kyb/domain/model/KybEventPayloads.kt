// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The wire payloads of `openbank.kyb.events`, one data class per event type (ADR-0284).
 *
 * ONE CLASS PER TYPE, and deliberately so: the ADR-0006 contract-agreement gate pairs each
 * AsyncAPI message with the data class whose companion declares that `EVENT_TYPE` and compares
 * the documented properties against the CONSTRUCTOR properties. A shared "envelope plus a map"
 * would leave the contract unchecked — a field could be added here, or documented and never sent,
 * and CI would agree with both. The repetition is what makes the document true.
 *
 * **No representative's date of birth is ever on the wire.** `signerName` is on the invitation
 * event only, where the initiator already knows it and the operator cockpit needs it to render
 * the invitation; everything else about a person stays inside the case record.
 */
sealed interface KybEventPayload {
    val eventType: String
    val caseId: UUID
    val status: CaseStatus
    val identifierScheme: IdentifierScheme
    val identifier: String
    val country: String?
    val legalName: String?
    val legalFormClass: LegalFormClass?
    val initiatorPartyId: UUID
    val entityPartyId: UUID?
    val requiredSignatures: Int?
    val signedCount: Int
    val occurredAt: Instant
    val actorId: String
    val actorType: String
    val sourceService: String
}

/** Carried by the two events that exist because a human has to look at the case. */
sealed interface ReviewPayload : KybEventPayload {
    val reviewReason: String?
}

/** Carried by the two events about one signer moving through the case. */
sealed interface SignerPayload : KybEventPayload {
    val signerId: UUID
    val signerName: String?
    val signerPartyId: UUID?
}

/** A case was opened for a business identifier. */
data class BusinessOnboardingStarted(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
) : KybEventPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_ONBOARDING_STARTED"
    }
}

/** The register answered and the entity party was minted in party-service. */
data class BusinessRegistryVerified(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
) : KybEventPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_REGISTRY_VERIFIED"
    }
}

/** A human must decide: unknown identifier, unparseable representation rule, unverified extract, initiator not listed, or the register was down. */
data class BusinessOnboardingReviewRequired(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
    override val reviewReason: String?,
) : KybEventPayload,
    ReviewPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_ONBOARDING_REVIEW_REQUIRED"
    }
}

/** The initiator invited a listed representative to co-sign. */
data class BusinessSignerInvited(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
    override val signerId: UUID,
    override val signerName: String?,
    override val signerPartyId: UUID?,
) : KybEventPayload,
    SignerPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_SIGNER_INVITED"
    }
}

/** An invited representative onboarded as themselves and bound their own party to the case. */
data class BusinessSignerIdentified(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
    override val signerId: UUID,
    override val signerName: String?,
    override val signerPartyId: UUID?,
) : KybEventPayload,
    SignerPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_SIGNER_IDENTIFIED"
    }
}

/** The required number of distinct verified signers has signed; mandates were granted in party-service. */
data class BusinessAgreementSigned(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
) : KybEventPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_AGREEMENT_SIGNED"
    }
}

/** Signed AND the entity party passed the KYC+AML gate (ADR-0267) — the relationship is live. */
data class BusinessOnboardingCompleted(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
) : KybEventPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_ONBOARDING_COMPLETED"
    }
}

/** An operator rejected the case. */
data class BusinessOnboardingRejected(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
    override val reviewReason: String?,
) : KybEventPayload,
    ReviewPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_ONBOARDING_REJECTED"
    }
}

/** Abandoned by the initiator, or by the Temporal idle/invitation timer (actorId `temporal-timer`). */
data class BusinessOnboardingAbandoned(
    override val eventType: String,
    override val caseId: UUID,
    override val status: CaseStatus,
    override val identifierScheme: IdentifierScheme,
    override val identifier: String,
    override val country: String?,
    override val legalName: String?,
    override val legalFormClass: LegalFormClass?,
    override val initiatorPartyId: UUID,
    override val entityPartyId: UUID?,
    override val requiredSignatures: Int?,
    override val signedCount: Int,
    override val occurredAt: Instant,
    override val actorId: String,
    override val actorType: String,
    override val sourceService: String,
) : KybEventPayload {
    companion object {
        const val EVENT_TYPE = "BUSINESS_ONBOARDING_ABANDONED"
    }
}
