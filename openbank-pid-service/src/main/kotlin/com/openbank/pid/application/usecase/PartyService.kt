// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.libs.domain.case.CaseId
import com.openbank.libs.domain.case.CaseReasonCode
import com.openbank.libs.domain.case.CaseStatus
import com.openbank.libs.domain.case.CaseTransition
import com.openbank.libs.domain.case.CaseTransitionEngine
import com.openbank.libs.domain.case.CaseTransitionResult
import com.openbank.libs.domain.case.CaseType
import com.openbank.pid.application.port.`in`.*
import com.openbank.pid.application.port.out.*
import com.openbank.pid.domain.event.*
import com.openbank.pid.domain.model.*
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class PartyService(
    private val partyRepository: PartyRepository,
    private val relationshipRepository: PartyRelationshipRepository,
    private val eventPublisher: PartyEventPublisher,
    /**
     * Used to compute the RČ blind index at party creation time (ADR-0072).
     * Injected as a concrete type; no circular dependency because [PartyService] does not
     * implement [ResolveIdentityUseCase] and Quarkus CDI resolves the graph lazily.
     */
    private val identityResolutionService: IdentityResolutionService,
    private val evidenceLinkPort: EvidenceLinkPort,
    private val clock: Clock,
) : CreatePartyUseCase,
    GetPartyUseCase,
    UpdatePartyUseCase,
    ManageRelationshipUseCase {

    private val caseTransitionEngine = CaseTransitionEngine()

    override suspend fun createParty(command: CreatePartyCommand): Party {
        command.bankIdSub?.let {
            if (partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, it)) {
                throw PartyAlreadyExistsException("Party with bankID sub $it already exists")
            }
        }

        val now = OffsetDateTime.now(clock)
        val partyId = UUID.randomUUID()
        val relationshipId = UUID.randomUUID()
        val caseInitializedAt = now
        val caseLifecycle = PartyCaseLifecycle(
            caseId = CaseId.new(),
            caseType = CaseType.PID_VERIFICATION,
            status = CaseStatus.OPEN,
            lastActor = lifecycleActorFor(command.verificationSource, command.onboardingChannel),
            lastReasonCode = CaseReasonCode.CREATED,
            lastTransitionAt = caseInitializedAt,
            metadata = lifecycleMetadata(
                source = command.verificationSource.name,
                channel = command.onboardingChannel.name,
                reason = null,
                extra = mapOf(
                    "partyId" to partyId.toString(),
                    "initialRole" to command.initialRole.name,
                ),
            ),
        )

        // ── Blind index for Czech RČ (ADR-0072) ──────────────────────────────────
        // If birthNumberRaw is supplied, compute the HMAC-SHA256 blind index and include it as
        // a BIRTH_NUMBER external-id. The plaintext is never stored or logged — it is discarded
        // after this computation. When the pepper is absent (CI, local dev without Vault),
        // computeBlindIndex returns null and the BIRTH_NUMBER row is simply not created.
        val birthNumberBlindIndex: String? = command.birthNumberRaw
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                identityResolutionService.computeBlindIndex(raw).also { idx ->
                    if (idx == null) {
                        Log.warn(
                            "PartyService.createParty: birth-number-pepper absent or RČ invalid — " +
                                "BIRTH_NUMBER blind index will NOT be stored for party $partyId",
                        )
                    }
                }
            }

        val party = Party(
            id = partyId,
            partyType = command.partyType,
            status = PartyStatus.ACTIVE,
            externalIds = buildList {
                command.bankIdSub?.let { add(ExternalId(ExternalIdType.BANKID_SUB, it, now)) }
                birthNumberBlindIndex?.let { add(ExternalId(ExternalIdType.BIRTH_NUMBER, it, now)) }
            },
            coreAttributes = CoreAttributes(
                givenName = command.givenName,
                familyName = command.familyName,
                birthdate = command.birthdate,
                birthNumberEncrypted = command.birthNumberEncrypted,
                gender = null,
                birthplace = null,
                nationalities = command.nationalities,
                idDocuments = emptyList(),
                verificationSource = command.verificationSource,
                verifiedAt = now,
            ),
            addressAttributes = null,
            contactAttributes = ContactAttributes(
                email = null,
                emailVerifiedAt = null,
                phone = null,
                phoneVerifiedAt = null,
            ),
            kycAttributes = KycAttributes(
                kycLevel = KycLevel.BASIC,
                kycCompletedAt = now,
                kycExpiresAt = now.plusYears(1),
                amlRiskScore = AmlRiskScore.LOW,
                pepFlag = false,
                sanctionsFlag = false,
                uboVerifiedAt = null,
                lastAmlReviewAt = now,
            ),
            relationships = listOf(
                PartyRelationship(
                    id = relationshipId,
                    partyId = partyId,
                    role = command.initialRole,
                    status = RelationshipStatus.ACTIVE,
                    onboardedAt = now,
                    onboardingChannel = command.onboardingChannel,
                    terminatedAt = null,
                    terminationReason = null,
                ),
            ),
            caseLifecycle = caseLifecycle,
            createdAt = now, updatedAt = now, version = 0,
        )

        val saved = partyRepository.save(party)

        eventPublisher.publish(
            PartyCreatedEvent(
                aggregateId = partyId,
                partyType = command.partyType.name,
                verificationSource = command.verificationSource.name,
                givenName = command.givenName,
                familyName = command.familyName,
            ),
        )
        eventPublisher.publish(
            CaseCreatedEvent(
                aggregateId = partyId,
                caseId = caseLifecycle.caseId,
                caseType = caseLifecycle.caseType,
                status = caseLifecycle.status,
                actor = caseLifecycle.lastActor,
                reasonCode = caseLifecycle.lastReasonCode,
            ),
        )
        eventPublisher.publish(
            RelationshipAddedEvent(
                aggregateId = partyId,
                relationshipId = relationshipId,
                role = command.initialRole,
                channel = command.onboardingChannel,
            ),
        )

        return saved
    }

    override suspend fun getById(id: UUID): Party =
        partyRepository.findById(id) ?: throw PartyNotFoundException("Party $id not found")

    override suspend fun getByExternalId(type: ExternalIdType, value: String): Party =
        partyRepository.findByExternalId(type, value)
            ?: throw PartyNotFoundException("Party with $type=$value not found")

    override suspend fun search(query: PartySearchQuery): List<Party> = partyRepository.search(query)

    override suspend fun syncFromBankId(command: SyncFromBankIdCommand): Party {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)

        val updatedExternalIds = party.externalIds
            .filter { it.type != ExternalIdType.BANKID_SUB }
            .plus(ExternalId(ExternalIdType.BANKID_SUB, command.bankIdSub, now))

        val updated = party.copy(
            externalIds = updatedExternalIds,
            coreAttributes = party.coreAttributes.copy(
                givenName = command.givenName,
                familyName = command.familyName,
                birthdate = command.birthdate,
                birthNumberEncrypted = command.birthNumberEncrypted ?: party.coreAttributes.birthNumberEncrypted,
                gender = command.gender ?: party.coreAttributes.gender,
                birthplace = command.birthplace ?: party.coreAttributes.birthplace,
                nationalities = command.nationalities.ifEmpty { party.coreAttributes.nationalities },
                idDocuments = command.idDocuments.ifEmpty { party.coreAttributes.idDocuments },
                verificationSource = VerificationSource.BANKID,
                verifiedAt = now,
            ),
            contactAttributes = party.contactAttributes.copy(
                email = command.email ?: party.contactAttributes.email,
                emailVerifiedAt = if (command.email != null) now else party.contactAttributes.emailVerifiedAt,
                phone = command.phone ?: party.contactAttributes.phone,
                phoneVerifiedAt = if (command.phone != null) now else party.contactAttributes.phoneVerifiedAt,
            ),
            updatedAt = now,
            version = party.version + 1,
        )

        val saved = partyRepository.update(updated)
        eventPublisher.publish(
            PartyVerifiedEvent(
                aggregateId = command.partyId,
                verificationSource = VerificationSource.BANKID.name,
                kycLevel = saved.kycAttributes.kycLevel,
            ),
        )
        return saved
    }

    override suspend fun syncFromRob(command: SyncFromRobCommand): Party {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)

        val updatedExternalIds = party.externalIds
            .filter { it.type != ExternalIdType.ROB_AIFO }
            .plus(ExternalId(ExternalIdType.ROB_AIFO, command.robAifo, now))

        val updated = party.copy(
            externalIds = updatedExternalIds,
            addressAttributes = AddressAttributes(
                permanentAddress = command.permanentAddress,
                mailingAddress = command.mailingAddress,
                robSyncedAt = command.syncedAt,
            ),
            updatedAt = now,
            version = party.version + 1,
        )

        val saved = partyRepository.update(updated)
        eventPublisher.publish(
            AddressUpdatedFromRobEvent(
                aggregateId = command.partyId,
                syncedAt = command.syncedAt,
            ),
        )
        return saved
    }

    override suspend fun updateContact(command: UpdateContactCommand): Party {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)

        val updated = party.copy(
            contactAttributes = party.contactAttributes.copy(
                email = command.email ?: party.contactAttributes.email,
                emailVerifiedAt = if (command.email != null) now else party.contactAttributes.emailVerifiedAt,
                phone = command.phone ?: party.contactAttributes.phone,
                phoneVerifiedAt = if (command.phone != null) now else party.contactAttributes.phoneVerifiedAt,
                preferredLanguage = command.preferredLanguage ?: party.contactAttributes.preferredLanguage,
                dataBoxId = command.dataBoxId ?: party.contactAttributes.dataBoxId,
            ),
            updatedAt = now,
            version = party.version + 1,
        )

        return partyRepository.update(updated)
    }

    override suspend fun updateKyc(command: UpdateKycCommand): Party {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)
        val previousLevel = party.kycAttributes.kycLevel

        val updated = party.copy(
            kycAttributes = party.kycAttributes.copy(
                kycLevel = command.kycLevel,
                kycCompletedAt = now,
                kycExpiresAt = now.plusYears(1),
                amlRiskScore = command.amlRiskScore,
                pepFlag = command.pepFlag,
                sanctionsFlag = command.sanctionsFlag,
                lastAmlReviewAt = now,
            ),
            updatedAt = now,
            version = party.version + 1,
        )

        val saved = partyRepository.update(updated)

        if (previousLevel != command.kycLevel) {
            eventPublisher.publish(
                KycLevelChangedEvent(
                    aggregateId = command.partyId,
                    previousLevel = previousLevel,
                    newLevel = command.kycLevel,
                ),
            )
        }

        return saved
    }

    override suspend fun changeStatus(command: ChangePartyStatusCommand): Party {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)
        val previousStatus = party.status

        val updated = party.copy(
            status = command.newStatus,
            updatedAt = now,
            version = party.version + 1,
        )

        val saved = partyRepository.update(updated)
        eventPublisher.publish(
            PartyStatusChangedEvent(
                aggregateId = command.partyId,
                previousStatus = previousStatus,
                newStatus = command.newStatus,
                reason = command.reason,
            ),
        )
        return saved
    }

    override suspend fun transitionCase(command: TransitionPartyCaseCommand): Party {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)
        val caseLifecycle = party.caseLifecycle
            ?: throw IllegalStateException("PID case lifecycle is not initialized for party ${command.partyId}")

        val transition = CaseTransition(
            caseId = caseLifecycle.caseId,
            caseType = caseLifecycle.caseType,
            fromStatus = caseLifecycle.status,
            toStatus = command.toStatus,
            reasonCode = command.reasonCode,
            actor = command.actor.trim(),
            occurredAt = now.toInstant(),
            metadata = lifecycleMetadata(
                source = caseLifecycle.metadata["source"] ?: party.coreAttributes.verificationSource.name,
                channel = caseLifecycle.metadata["channel"]
                    ?: party.relationships.firstOrNull()?.onboardingChannel?.name
                    ?: OnboardingChannel.API.name,
                reason = command.reason,
                extra = command.metadata + mapOf("partyId" to party.id.toString()),
            ),
        )

        return when (val result = caseTransitionEngine.apply(transition)) {
            is CaseTransitionResult.Applied -> {
                val updated = party.copy(
                    caseLifecycle = caseLifecycle.copy(
                        status = result.newStatus,
                        lastActor = result.timelineEvent.actor,
                        lastReasonCode = result.timelineEvent.reasonCode,
                        lastTransitionAt = OffsetDateTime.ofInstant(result.timelineEvent.occurredAt, now.offset),
                        metadata = result.timelineEvent.metadata,
                    ),
                    updatedAt = now,
                    version = party.version + 1,
                )
                val saved = partyRepository.update(updated)
                eventPublisher.publish(
                    CaseTransitionedEvent(
                        aggregateId = command.partyId,
                        caseId = caseLifecycle.caseId,
                        caseType = caseLifecycle.caseType,
                        fromStatus = caseLifecycle.status,
                        toStatus = result.newStatus,
                        reasonCode = command.reasonCode,
                        actor = command.actor.trim(),
                    ),
                )
                saved
            }

            is CaseTransitionResult.Rejected -> throw InvalidPartyCaseTransitionException(result.reason)
        }
    }

    override suspend fun linkCaseEvidence(command: LinkCaseEvidenceCommand): Party {
        val party = getById(command.partyId)
        val caseLifecycle = party.caseLifecycle
            ?: throw IllegalStateException("PID case lifecycle is not initialized for party ${command.partyId}")

        evidenceLinkPort.recordLink(
            partyId = command.partyId,
            caseId = caseLifecycle.caseId.value.toString(),
            evidenceRef = command.evidenceRef,
            actor = command.actor.trim(),
        )
        eventPublisher.publish(
            CaseEvidenceLinkedEvent(
                aggregateId = command.partyId,
                caseId = caseLifecycle.caseId,
                evidenceRef = command.evidenceRef,
                actor = command.actor.trim(),
                linkedAt = command.linkedAt.toInstant(),
            ),
        )

        return party
    }

    override suspend fun linkExternalId(command: LinkExternalIdCommand): Party {
        val party = getById(command.partyId)

        // Idempotent: the exact (type, value) is already on this party — nothing to do.
        if (party.externalIds.any { it.type == command.type && it.value == command.value }) {
            return party
        }
        // The same identifier must not belong to two parties (the UNIQUE(id_type, id_value)
        // backstop, surfaced as a clean conflict rather than a constraint violation).
        if (partyRepository.existsByExternalId(command.type, command.value)) {
            throw PartyAlreadyExistsException(
                "external id ${command.type}=${command.value} is already linked to another party",
            )
        }

        val now = OffsetDateTime.now(clock)
        val updated = party.copy(
            externalIds = party.externalIds + ExternalId(command.type, command.value, now),
            updatedAt = now,
            version = party.version + 1,
        )
        val saved = partyRepository.update(updated)
        eventPublisher.publish(ExternalIdLinkedEvent(aggregateId = command.partyId, externalIdType = command.type.name))
        return saved
    }

    override suspend fun addRelationship(command: AddRelationshipCommand): PartyRelationship {
        val party = getById(command.partyId)
        val now = OffsetDateTime.now(clock)

        val existing = party.relationships.firstOrNull {
            it.role == command.role && it.status == RelationshipStatus.ACTIVE
        }
        if (existing != null) {
            throw RelationshipAlreadyExistsException(
                "Party ${command.partyId} already has active role ${command.role}",
            )
        }

        val relationship = PartyRelationship(
            id = UUID.randomUUID(),
            partyId = command.partyId,
            role = command.role,
            status = RelationshipStatus.ACTIVE,
            onboardedAt = now,
            onboardingChannel = command.onboardingChannel,
            terminatedAt = null,
            terminationReason = null,
        )

        val saved = relationshipRepository.save(relationship)
        eventPublisher.publish(
            RelationshipAddedEvent(
                aggregateId = command.partyId,
                relationshipId = saved.id,
                role = command.role,
                channel = command.onboardingChannel,
            ),
        )
        return saved
    }

    override suspend fun terminateRelationship(command: TerminateRelationshipCommand): PartyRelationship {
        val relationship = relationshipRepository.findById(command.relationshipId)
            ?: throw PartyNotFoundException("Relationship ${command.relationshipId} not found")

        if (relationship.partyId != command.partyId) {
            throw IllegalArgumentException("Relationship does not belong to party ${command.partyId}")
        }

        val now = OffsetDateTime.now(clock)
        val updated = relationship.copy(
            status = RelationshipStatus.TERMINATED,
            terminatedAt = now,
            terminationReason = command.reason,
        )

        val saved = relationshipRepository.update(updated)
        eventPublisher.publish(
            RelationshipTerminatedEvent(
                aggregateId = command.partyId,
                relationshipId = command.relationshipId,
                role = relationship.role,
                reason = command.reason,
            ),
        )
        return saved
    }

    private fun lifecycleActorFor(
        verificationSource: VerificationSource,
        onboardingChannel: OnboardingChannel,
    ): String = "pid:${verificationSource.name.lowercase()}:${onboardingChannel.name.lowercase()}"

    private fun lifecycleMetadata(
        source: String,
        channel: String,
        reason: String?,
        extra: Map<String, String>,
    ): Map<String, String> = linkedMapOf(
        "source" to source,
        "channel" to channel,
    ).apply {
        reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
        extra.toSortedMap().forEach { (key, value) -> put(key, value) }
    }
}

class PartyNotFoundException(message: String) : RuntimeException(message)
class PartyAlreadyExistsException(message: String) : RuntimeException(message)
class RelationshipAlreadyExistsException(message: String) : RuntimeException(message)
class InvalidPartyCaseTransitionException(message: String) : RuntimeException(message)
