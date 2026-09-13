// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import com.openbank.libs.domain.event.EventActor
import java.time.Instant
import java.util.UUID

/**
 * A business-onboarding lifecycle event: the wire type, the aggregate it belongs to (the case),
 * when it happened, and the typed [KybEventPayload] that goes on `openbank.kyb.events`.
 * Serialization happens in the infrastructure layer; this stays framework-free (ADR-0002/0122).
 */
data class KybEvent(
    val eventType: String,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val payload: KybEventPayload,
)

/** Builds the case lifecycle events. One builder per wire event type (ADR-0284). */
@Suppress("TooManyFunctions") // one builder per wire event type — the count is the contract's
object KybEvents {

    const val STARTED = BusinessOnboardingStarted.EVENT_TYPE
    const val REGISTRY_VERIFIED = BusinessRegistryVerified.EVENT_TYPE
    const val REVIEW_REQUIRED = BusinessOnboardingReviewRequired.EVENT_TYPE
    const val SIGNER_INVITED = BusinessSignerInvited.EVENT_TYPE
    const val SIGNER_IDENTIFIED = BusinessSignerIdentified.EVENT_TYPE
    const val AGREEMENT_SIGNED = BusinessAgreementSigned.EVENT_TYPE
    const val COMPLETED = BusinessOnboardingCompleted.EVENT_TYPE
    const val REJECTED = BusinessOnboardingRejected.EVENT_TYPE
    const val ABANDONED = BusinessOnboardingAbandoned.EVENT_TYPE

    /** Module directory minus the `openbank-` prefix — the fleet's audit attribution convention. */
    const val SOURCE_SERVICE = "kyb-service"

    /** A case was opened for a business identifier. */
    fun started(case: BusinessOnboardingCase, at: Instant, actorId: String): KybEvent {
        val actor = actorId
        val f = fields(case)
        return KybEvent(
            eventType = STARTED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessOnboardingStarted(
                eventType = STARTED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
            ),
        )
    }

    /** The register answered and the entity party was minted in party-service. */
    fun registryVerified(case: BusinessOnboardingCase, at: Instant): KybEvent {
        val actor = system("registry")
        val f = fields(case)
        return KybEvent(
            eventType = REGISTRY_VERIFIED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessRegistryVerified(
                eventType = REGISTRY_VERIFIED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
            ),
        )
    }

    /** A human must decide — the reason is on the event, so a cockpit never has to guess. */
    fun reviewRequired(case: BusinessOnboardingCase, at: Instant): KybEvent {
        val actor = system("registry")
        val f = fields(case)
        return KybEvent(
            eventType = REVIEW_REQUIRED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessOnboardingReviewRequired(
                eventType = REVIEW_REQUIRED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
                reviewReason = case.reviewReason,
            ),
        )
    }

    /** An operator rejected the case. */
    fun rejected(case: BusinessOnboardingCase, at: Instant, actorId: String): KybEvent {
        val actor = actorId
        val f = fields(case)
        return KybEvent(
            eventType = REJECTED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessOnboardingRejected(
                eventType = REJECTED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
                reviewReason = case.reviewReason,
            ),
        )
    }

    /** The initiator invited a listed representative to co-sign. */
    fun signerInvited(case: BusinessOnboardingCase, signer: Signer, at: Instant, actorId: String): KybEvent {
        val actor = actorId
        val f = fields(case)
        return KybEvent(
            eventType = SIGNER_INVITED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessSignerInvited(
                eventType = SIGNER_INVITED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
                signerId = signer.id,
                signerName = signer.fullName,
                signerPartyId = signer.partyId,
            ),
        )
    }

    /** An invited representative onboarded as themselves and bound their own party. */
    fun signerIdentified(case: BusinessOnboardingCase, signer: Signer, at: Instant): KybEvent {
        val actor = signer.partyId.toString()
        val f = fields(case)
        return KybEvent(
            eventType = SIGNER_IDENTIFIED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessSignerIdentified(
                eventType = SIGNER_IDENTIFIED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
                signerId = signer.id,
                signerName = null,
                signerPartyId = signer.partyId,
            ),
        )
    }

    /** Every required signature is in; mandates were granted in party-service. */
    fun agreementSigned(case: BusinessOnboardingCase, at: Instant, actorId: String): KybEvent {
        val actor = actorId
        val f = fields(case)
        return KybEvent(
            eventType = AGREEMENT_SIGNED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessAgreementSigned(
                eventType = AGREEMENT_SIGNED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
            ),
        )
    }

    /** Signed AND the entity party passed the KYC+AML gate (ADR-0267). */
    fun completed(case: BusinessOnboardingCase, at: Instant): KybEvent {
        val actor = system("party-events-in")
        val f = fields(case)
        return KybEvent(
            eventType = COMPLETED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessOnboardingCompleted(
                eventType = COMPLETED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
            ),
        )
    }

    /** By the initiator, or by the Temporal idle/invitation timer. */
    fun abandoned(case: BusinessOnboardingCase, at: Instant, actorId: String): KybEvent {
        val actor = actorId
        val f = fields(case)
        return KybEvent(
            eventType = ABANDONED,
            aggregateId = case.id,
            occurredAt = at,
            payload = BusinessOnboardingAbandoned(
                eventType = ABANDONED,
                caseId = f.caseId,
                status = f.status,
                identifierScheme = f.scheme,
                identifier = f.identifier,
                country = f.country,
                legalName = f.legalName,
                legalFormClass = f.legalFormClass,
                initiatorPartyId = f.initiator,
                entityPartyId = f.entity,
                requiredSignatures = f.required,
                signedCount = f.signed,
                occurredAt = at,
                actorId = actor,
                actorType = actorType(actor),
                sourceService = SOURCE_SERVICE,
            ),
        )
    }

    /** The fields every payload shares, read off the case once so no builder can spell one differently. */
    private data class Fields(
        val caseId: UUID,
        val status: CaseStatus,
        val scheme: IdentifierScheme,
        val identifier: String,
        val country: String?,
        val legalName: String?,
        val legalFormClass: LegalFormClass?,
        val initiator: UUID,
        val entity: UUID?,
        val required: Int?,
        val signed: Int,
    )

    private fun fields(case: BusinessOnboardingCase) = Fields(
        caseId = case.id,
        status = case.status,
        scheme = case.identifier.scheme,
        identifier = case.identifier.value,
        country = case.identifier.country ?: case.extract?.registeredAddress?.countryCode,
        legalName = case.extract?.legalName,
        legalFormClass = case.extract?.legalFormClass,
        initiator = case.initiatorPartyId,
        entity = case.entityPartyId,
        required = case.requiredSignatures,
        signed = case.signedCount,
    )

    private fun system(mechanism: String) = EventActor.system(SOURCE_SERVICE, mechanism)

    private fun actorType(actorId: String) =
        if (EventActor.isSystem(actorId)) EventActor.TYPE_SYSTEM else ACTOR_TYPE_CUSTOMER

    private const val ACTOR_TYPE_CUSTOMER = "CUSTOMER"
}
