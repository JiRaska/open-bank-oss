// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.KycOutboxRepository
import com.openbank.kyc.application.port.out.ScreeningTriggerRepository
import com.openbank.kyc.domain.model.ScreeningTrigger
import com.openbank.kyc.domain.model.ScreeningTriggerDiff
import com.openbank.kyc.domain.model.ScreeningTriggerEvents
import com.openbank.kyc.domain.model.ScreeningTriggerFailure
import com.openbank.kyc.domain.model.ScreeningTriggerState
import com.openbank.kyc.infrastructure.persistence.entity.ScreeningTriggerEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID

/**
 * kyc_screening_triggers (ADR-0256). Insert idempotency uses
 * `INSERT ... ON CONFLICT (publication_id, party_id) DO NOTHING` rather than catch-the-
 * constraint-violation: the row count answers "did I win the open attempt" without parsing a
 * PersistenceException whose cause chain differs between Hibernate Reactive versions.
 */
@ApplicationScoped
class ScreeningTriggerRepositoryImpl(private val outboxRepository: KycOutboxRepository) :
    ScreeningTriggerRepository,
    PanacheRepository<ScreeningTriggerEntity> {

    @Inject
    lateinit var objectMapper: ObjectMapper

    override suspend fun insertIfAbsent(trigger: ScreeningTrigger): Boolean {
        require(
            trigger.state == ScreeningTriggerState.PENDING &&
                trigger.attempts == 1 &&
                trigger.nextAttemptAt?.isAfter(trigger.createdAt) == true,
        ) {
            "A pending screening trigger requires an initial lease"
        }
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_IF_ABSENT)
                    .setParameter("triggerId", trigger.triggerId)
                    .setParameter("partyId", trigger.partyId)
                    .setParameter("triggerType", ScreeningTrigger.TRIGGER_TYPE_SANCTIONS_LIST_CHANGED)
                    .setParameter("publicationId", trigger.publicationId)
                    .setParameter("chunkIndex", trigger.chunkIndex)
                    .setParameter("listType", trigger.listType)
                    .setParameter("diff", objectMapper.writeValueAsString(trigger.diff))
                    .setParameter("state", ScreeningTriggerState.PENDING.name)
                    .setParameter("attempts", trigger.attempts)
                    .setParameter("leaseUntil", trigger.nextAttemptAt)
                    .executeUpdate()
            }
        }.awaitSuspending() == 1
    }

    override suspend fun recordOpened(trigger: ScreeningTrigger, coordinatorCaseId: String?, at: Instant) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(MARK_OPENED)
                    .setParameter("coordinatorCaseId", coordinatorCaseId)
                    .setParameter("resolvedAt", at)
                    .setParameter("triggerId", trigger.triggerId)
                    .setParameter("claimedAttempts", trigger.attempts)
                    .setParameter("leaseUntil", trigger.nextAttemptAt)
                    .executeUpdate().invoke { changed -> check(changed == 1) { "Screening trigger lease lost" } }
            }
        }.awaitSuspending()
    }

    /**
     * The state flip and the KYC_COORDINATOR_OPEN_FAILED outbox row commit together or not at
     * all (ADR-0256 contract: "written transactionally with the trigger state") — a trigger can
     * never read OPEN_FAILED without the operator-visible event existing.
     */
    override suspend fun recordOpenFailed(
        trigger: ScreeningTrigger,
        reason: ScreeningTriggerFailure,
        attempts: Int,
        nextAttemptAt: Instant?,
        at: Instant,
    ) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(MARK_OPEN_FAILED)
                    .setParameter("state", ScreeningTriggerState.OPEN_FAILED.name)
                    .setParameter("reason", reason.name)
                    .setParameter("attempts", attempts)
                    .setParameter("nextAttemptAt", nextAttemptAt)
                    .setParameter("triggerId", trigger.triggerId)
                    .setParameter("claimedAttempts", trigger.attempts)
                    .setParameter("leaseUntil", trigger.nextAttemptAt)
                    .executeUpdate().invoke { changed -> check(changed == 1) { "Screening trigger lease lost" } }
                    .flatMap {
                        val event = ScreeningTriggerEvents.coordinatorOpenFailed(
                            trigger.copy(
                                state = ScreeningTriggerState.OPEN_FAILED,
                                attempts = attempts,
                                failedReason = reason,
                                nextAttemptAt = nextAttemptAt,
                            ),
                            at,
                        )
                        outboxRepository.persistInTransaction(
                            OutboxMessage(
                                aggregateId = event.aggregateId,
                                eventType = event.eventType,
                                payload = objectMapper.writeValueAsString(event.envelope),
                                createdAt = event.occurredAt,
                            ),
                        )
                    }
            }
        }.awaitSuspending()
    }

    override suspend fun findRetryable(now: Instant, limit: Int): List<ScreeningTrigger> = Panache.withSession {
        find(
            "state IN ?1 AND ((nextAttemptAt IS NOT NULL AND nextAttemptAt <= ?2) " +
                "OR (state = 'PENDING' AND nextAttemptAt IS NULL AND createdAt <= ?3)) " +
                "AND (state = 'PENDING' OR attempts < 5) " +
                "ORDER BY nextAttemptAt ASC, id ASC",
            listOf(ScreeningTriggerState.PENDING.name, ScreeningTriggerState.OPEN_FAILED.name),
            now,
            now.minusSeconds(LEASE_SECONDS),
        ).page(0, limit).list()
    }.awaitSuspending().map { it.toDomain(objectMapper) }

    override suspend fun claim(trigger: ScreeningTrigger, now: Instant, leaseUntil: Instant): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(CLAIM)
                    .setParameter("triggerId", trigger.triggerId)
                    .setParameter("attempts", trigger.attempts)
                    .setParameter("now", now)
                    .setParameter("legacyCutoff", now.minusSeconds(LEASE_SECONDS))
                    .setParameter("leaseUntil", leaseUntil)
                    .executeUpdate()
            }
        }.awaitSuspending() == 1

    override suspend fun findByTriggerId(triggerId: UUID): ScreeningTrigger? = Panache.withSession {
        find("triggerId", triggerId).firstResult()
    }.awaitSuspending()?.toDomain(objectMapper)

    private companion object {
        const val LEASE_SECONDS = 600L

        const val INSERT_IF_ABSENT = """
            INSERT INTO kyc_screening_triggers
                (trigger_id, party_id, trigger_type, publication_id, chunk_index, list_type, diff, state, attempts, next_attempt_at)
            VALUES
                (:triggerId, :partyId, :triggerType, :publicationId, :chunkIndex, :listType, :diff, :state, :attempts, :leaseUntil)
            ON CONFLICT (publication_id, party_id) DO NOTHING
        """

        const val CLAIM = """
            UPDATE kyc_screening_triggers
            SET state = 'PENDING', attempts = attempts + 1, next_attempt_at = :leaseUntil
            WHERE trigger_id = :triggerId AND attempts = :attempts
                AND (state = 'PENDING' OR attempts < 5)
                AND state IN ('PENDING', 'OPEN_FAILED')
                AND ((next_attempt_at IS NOT NULL AND next_attempt_at <= :now)
                    OR (state = 'PENDING' AND next_attempt_at IS NULL AND created_at <= :legacyCutoff))
        """

        const val MARK_OPENED = """
            UPDATE kyc_screening_triggers
            SET state = 'OPENED', coordinator_case_id = :coordinatorCaseId, resolved_at = :resolvedAt
            WHERE trigger_id = :triggerId AND state = 'PENDING'
                AND attempts = :claimedAttempts AND next_attempt_at = :leaseUntil
        """

        const val MARK_OPEN_FAILED = """
            UPDATE kyc_screening_triggers
            SET state = :state, failed_reason = :reason, attempts = :attempts, next_attempt_at = :nextAttemptAt
            WHERE trigger_id = :triggerId AND state = 'PENDING'
                AND attempts = :claimedAttempts AND next_attempt_at = :leaseUntil
        """
    }
}

private fun ScreeningTriggerEntity.toDomain(objectMapper: ObjectMapper) = ScreeningTrigger(
    triggerId = triggerId,
    partyId = partyId,
    publicationId = publicationId,
    chunkIndex = chunkIndex ?: 0,
    listType = listType,
    diff = objectMapper.readValue(diff, ScreeningTriggerDiff::class.java),
    state = ScreeningTriggerState.valueOf(state),
    coordinatorCaseId = coordinatorCaseId,
    attempts = attempts,
    failedReason = failedReason?.let(ScreeningTriggerFailure::valueOf),
    nextAttemptAt = nextAttemptAt,
    createdAt = createdAt,
    resolvedAt = resolvedAt,
)
