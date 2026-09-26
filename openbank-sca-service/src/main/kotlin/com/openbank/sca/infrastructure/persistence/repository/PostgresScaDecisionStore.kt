// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sca.application.port.out.ScaDecisionStore
import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.domain.model.dynamicLinkingPayload
import com.openbank.sca.infrastructure.persistence.entity.EnrolledDeviceEntity
import com.openbank.sca.infrastructure.persistence.entity.ScaChallengeEntity
import com.openbank.sca.infrastructure.persistence.entity.ScaDeviceDecisionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** First decision and audit commit together; a cache loss cannot erase an acknowledged approval. */
@ApplicationScoped
class PostgresScaDecisionStore(
    private val challenges: ScaChallengeRepositoryImpl,
    private val devices: EnrolledDeviceRepositoryImpl,
    private val outbox: ScaOutboxRepositoryImpl,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ScaDecisionStore,
    PanacheRepository<ScaDeviceDecisionEntity> {
    override suspend fun record(decision: DeviceApprovalDecision, ttlSeconds: Long): Boolean {
        require(ttlSeconds > 0) { "Decision TTL must be positive" }
        return Panache.withTransaction {
            devices.find("credentialId", decision.credentialId)
                .withLock<EnrolledDeviceEntity>(LockModeType.PESSIMISTIC_WRITE)
                .firstResult<EnrolledDeviceEntity>().flatMap { device ->
                    if (device == null || device.revokedAt != null) {
                        Uni.createFrom().item(false)
                    } else {
                        recordForActiveDevice(decision, ttlSeconds, device)
                    }
                }
        }.awaitSuspending()
    }

    private fun recordForActiveDevice(
        decision: DeviceApprovalDecision,
        ttlSeconds: Long,
        device: EnrolledDeviceEntity,
    ): Uni<Boolean> =
        challenges.find("id", decision.challengeId).withLock<ScaChallengeEntity>(LockModeType.PESSIMISTIC_WRITE)
            .firstResult<ScaChallengeEntity>().flatMap { challenge ->
                if (challenge == null ||
                    challenge.partyId != device.partyId ||
                    !challenge.eligibleFor(decision, OffsetDateTime.now(clock))
                ) {
                    Uni.createFrom().item(false)
                } else {
                    acceptFirst(challenge, decision, ttlSeconds)
                }
            }

    override suspend fun find(challengeId: UUID): DeviceApprovalDecision? = Panache.withSession {
        find("challengeId = ?1 and expiresAt > ?2", challengeId, OffsetDateTime.now(clock))
            .firstResult<ScaDeviceDecisionEntity>()
    }.awaitSuspending()?.toDomain()

    private fun acceptFirst(
        challenge: ScaChallengeEntity,
        decision: DeviceApprovalDecision,
        ttlSeconds: Long,
    ): Uni<Boolean> =
        find("challengeId", decision.challengeId).firstResult<ScaDeviceDecisionEntity>().flatMap { existing ->
            val expiresAt = minOf(challenge.expiresAt, decision.decidedAt.plusSeconds(ttlSeconds))
            if (existing != null || !expiresAt.isAfter(OffsetDateTime.now(clock))) {
                Uni.createFrom().item(false)
            } else {
                val entity = decision.toEntity(challenge, expiresAt)
                persistAndFlush(entity).flatMap { Panache.getSession() }.flatMap { it.refresh(entity) }
                    .flatMap { outbox.persistInTransaction(auditMessage(entity, challenge.partyId)) }.replaceWith(true)
            }
        }

    private fun auditMessage(entity: ScaDeviceDecisionEntity, partyId: UUID): OutboxMessage {
        val eventId = Ids.newId()
        return OutboxMessage(
            eventId = eventId,
            aggregateId = entity.challengeId,
            eventType = "SCA_DEVICE_DECIDED",
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "eventId" to eventId.toString(),
                    "eventType" to "SCA_DEVICE_DECIDED",
                    "schemaVersion" to 1,
                    "sourceService" to "sca-service",
                    "aggregateType" to "SCA_CHALLENGE",
                    "aggregateId" to entity.challengeId.toString(),
                    "actorId" to entity.credentialId,
                    "actorType" to "DEVICE_CREDENTIAL",
                    "partyId" to partyId.toString(),
                    "credentialId" to entity.credentialId,
                    "decision" to entity.decision.name,
                    "signatureB64" to entity.signatureB64,
                    "signedPayloadB64" to entity.signedPayloadB64,
                    "occurredAt" to entity.decidedAt.toString(),
                    "expiresAt" to entity.expiresAt.toString(),
                    "challengeVersion" to entity.challengeVersion,
                ),
            ),
        )
    }
}

private fun ScaChallengeEntity.eligibleFor(decision: DeviceApprovalDecision, now: OffsetDateTime): Boolean =
    status == ScaStatus.PENDING && consumedAt == null && expiresAt.isAfter(now) && version == decision.challengeVersion

private fun DeviceApprovalDecision.toEntity(challenge: ScaChallengeEntity, expiry: OffsetDateTime) =
    ScaDeviceDecisionEntity().also {
        it.challengeId = challengeId
        it.credentialId = credentialId
        it.decision = decision
        it.signatureB64 = signatureB64
        it.signedPayloadB64 = Base64.getEncoder().encodeToString(challenge.toDomain().dynamicLinkingPayload(decision))
        it.decidedAt = decidedAt
        it.expiresAt = expiry
        it.challengeVersion = challengeVersion
        it.decidingPartyId = challenge.partyId
    }
