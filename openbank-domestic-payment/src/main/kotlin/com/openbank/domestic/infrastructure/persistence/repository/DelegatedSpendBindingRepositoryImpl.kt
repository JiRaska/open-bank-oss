// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.repository

import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.DelegatedSpendProjectionConflictException
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.ReservationProjectionApplyResult
import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.infrastructure.persistence.entity.DelegatedSpendBindingEntity
import com.openbank.libs.messaging.SyntheticTaintKafkaRail
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DelegatedSpendBindingRepositoryImpl(
    private val outboxRepository: DomesticPaymentOutboxRepositoryImpl,
    private val eventPublisher: DomesticPaymentEventPublisher,
    private val clock: Clock,
) : DelegatedSpendBindingRepository,
    PanacheRepository<DelegatedSpendBindingEntity> {

    override suspend fun applySnapshot(snapshot: DelegatedSpendReservationSnapshot): ReservationProjectionApplyResult =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                val canonicalSnapshot = snapshot.canonicalizedSourceTimestamps()
                val observedAt = Instant.now(clock)
                // Read INSIDE the transaction chain, not at the call site. The taint arrives on a
                // Kafka record header and SyntheticTaintKafkaRail turns it into ambient MDC +
                // OpenTelemetry baggage for the duration of the message; this is the point where
                // that has to still be true, because this is where the durable row is written.
                // Reading it here rather than at the consumer is what makes the reactive-context
                // propagation a tested property instead of an assumption -- see
                // DelegatedSpendReservationTaintIT.
                val tainted = SyntheticTaintKafkaRail.currentlyTainted()
                insertIfAbsent(session, canonicalSnapshot, observedAt, tainted).flatMap { inserted ->
                    lock(session, canonicalSnapshot.reservationId).map { entity ->
                        checkNotNull(entity) { "Reservation projection disappeared after insert" }
                        applyLocked(entity, canonicalSnapshot, observedAt, inserted > 0, tainted)
                    }
                }
            }
        }.awaitSuspending()

    override suspend fun finalizeAbsentBefore(cutoff: Instant, limit: Int): Int = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(FINALIZE_CANDIDATES_SQL, DelegatedSpendBindingEntity::class.java)
                .setParameter("pending", DelegatedSpendBindingState.PENDING.name)
                .setParameter("cutoff", cutoff)
                .setParameter("batchLimit", limit.coerceIn(1, MAX_FINALIZE_BATCH))
                .resultList
                .flatMap { candidates ->
                    val finalizedAt = Instant.now(clock)
                    candidates.forEach { entity ->
                        entity.bindingState = DelegatedSpendBindingState.FINALIZED_ABSENT.name
                        entity.finalizedAt = finalizedAt
                        entity.updatedAt = finalizedAt
                    }
                    candidates.fold(Uni.createFrom().voidItem()) { chain, entity ->
                        val binding = entity.toDomain()
                        chain.flatMap {
                            outboxRepository.persistWithinCurrentTransaction(
                                OutboxMessage(
                                    aggregateId = entity.reservationId,
                                    eventType = FINALIZED_ABSENT_OUTBOX_EVENT,
                                    payload = eventPublisher.delegatedSpendFinalizedAbsentPayload(binding),
                                    createdAt = finalizedAt,
                                    // From the PERSISTED column, never from ambient context: this
                                    // runs on a scheduler, long after the record that carried the
                                    // taint was acked.
                                    synthetic = entity.synthetic,
                                ),
                            ).replaceWithVoid()
                        }
                    }.replaceWith(candidates.size)
                }
        }
    }.awaitSuspending()

    override suspend fun findByReservationId(reservationId: UUID): DelegatedSpendBinding? = Panache.withSession {
        find("reservationId", reservationId).firstResult()
    }.awaitSuspending()?.toDomain()

    private fun insertIfAbsent(
        session: Mutiny.Session,
        snapshot: DelegatedSpendReservationSnapshot,
        observedAt: Instant,
        synthetic: Boolean,
    ): Uni<Int> {
        val terminal = snapshot.reservationState != DelegatedSpendReservationState.RESERVED
        return session.createNativeMutationQuery(INSERT_IF_ABSENT_SQL)
            .setParameter("reservationId", snapshot.reservationId)
            .setParameter("delegationId", snapshot.delegationId)
            .setParameter("grantorPartyId", snapshot.grantorPartyId)
            .setParameter("granteePartyId", snapshot.granteePartyId)
            .setParameter("resourceType", snapshot.resourceType)
            .setParameter("resourceId", snapshot.resourceId)
            .setParameter("operationType", snapshot.operationType)
            .setParameter("amount", snapshot.amount)
            .setParameter("currency", snapshot.currency)
            .setParameter("idempotencyKeyHash", snapshot.idempotencyKeyHash)
            .setParameter("reservationState", snapshot.reservationState.name)
            .setParameter("reservationVersion", snapshot.reservationVersion)
            .setParameter("schemaVersion", snapshot.schemaVersion)
            .setParameter("aggregateType", snapshot.aggregateType)
            .setParameter("sourceService", snapshot.sourceService)
            .setParameter("sourceCreatedAt", snapshot.createdAt)
            .setParameter("sourceSettledAt", snapshot.settledAt)
            .setParameter("sourceOccurredAt", snapshot.occurredAt)
            .setParameter("lastEventId", snapshot.eventId)
            .setParameter(
                "bindingState",
                if (terminal) {
                    DelegatedSpendBindingState.FINALIZED_ABSENT.name
                } else {
                    DelegatedSpendBindingState.PENDING.name
                },
            )
            .setParameter("finalizedAt", observedAt.takeIf { terminal })
            .setParameter("observedAt", observedAt)
            .setParameter("synthetic", synthetic)
            .executeUpdate()
    }

    private fun lock(session: Mutiny.Session, reservationId: UUID): Uni<DelegatedSpendBindingEntity?> =
        session.createQuery(
            "FROM DelegatedSpendBindingEntity WHERE reservationId = :reservationId",
            DelegatedSpendBindingEntity::class.java,
        ).setParameter("reservationId", reservationId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .singleResultOrNull

    private fun applyLocked(
        entity: DelegatedSpendBindingEntity,
        incoming: DelegatedSpendReservationSnapshot,
        observedAt: Instant,
        inserted: Boolean,
        synthetic: Boolean,
    ): ReservationProjectionApplyResult {
        val stored = entity.toDomain().snapshot
        if (!stored.hasSameImmutableTuple(incoming)) {
            projectionConflict(
                "Reservation ${incoming.reservationId} changed its immutable authorization tuple",
            )
        }
        // Monotonic, and applied before every early return: a duplicate or stale delivery that
        // carries the taint the first one lacked must still raise it. Never cleared -- an absent
        // header is "no claim", not "this is real", and clearing would be the unbounded, silent
        // direction the taint's whole default leans away from.
        if (synthetic) entity.synthetic = true
        if (inserted) return ReservationProjectionApplyResult.APPLIED
        if (incoming.reservationVersion < stored.reservationVersion) {
            return ReservationProjectionApplyResult.STALE_OR_DUPLICATE
        }
        if (incoming.reservationVersion == stored.reservationVersion) {
            if (!incoming.hasSameRevisionEvidence(stored)) {
                projectionConflict(
                    "Reservation ${incoming.reservationId} has contradictory revision ${incoming.reservationVersion}",
                )
            }
            return ReservationProjectionApplyResult.STALE_OR_DUPLICATE
        }
        if (
            stored.reservationVersion != DelegatedSpendReservationSnapshot.RESERVED_VERSION ||
            incoming.reservationVersion != DelegatedSpendReservationSnapshot.TERMINAL_VERSION
        ) {
            projectionConflict(
                "Reservation ${incoming.reservationId} skipped an unsupported revision",
            )
        }

        entity.reservationState = incoming.reservationState.name
        entity.reservationVersion = incoming.reservationVersion
        entity.sourceSettledAt = incoming.settledAt
        entity.sourceOccurredAt = incoming.occurredAt
        entity.lastEventId = incoming.eventId
        entity.updatedAt = observedAt
        if (entity.bindingState == DelegatedSpendBindingState.PENDING.name) {
            entity.bindingState = DelegatedSpendBindingState.FINALIZED_ABSENT.name
            entity.finalizedAt = observedAt
        }
        return ReservationProjectionApplyResult.APPLIED
    }

    private fun projectionConflict(message: String): Nothing = throw DelegatedSpendProjectionConflictException(message)

    companion object {
        const val FINALIZED_ABSENT_OUTBOX_EVENT = "domestic.delegated-spend.finalized-absent"
        private const val MAX_FINALIZE_BATCH = 500

        private const val INSERT_IF_ABSENT_SQL = """
            INSERT INTO domestic_delegated_spend_bindings (
                reservation_id, delegation_id, grantor_party_id, grantee_party_id, resource_type,
                resource_id, operation_type, amount, currency, idempotency_key_hash, reservation_state,
                reservation_version, schema_version, aggregate_type, source_service,
                source_created_at, source_settled_at, source_occurred_at, last_event_id,
                binding_state, payment_id, observed_at, bound_at, finalized_at, updated_at, synthetic
            ) VALUES (
                :reservationId, :delegationId, :grantorPartyId, :granteePartyId, :resourceType,
                :resourceId, :operationType, :amount, :currency, :idempotencyKeyHash, :reservationState,
                :reservationVersion, :schemaVersion, :aggregateType, :sourceService,
                :sourceCreatedAt, :sourceSettledAt, :sourceOccurredAt, :lastEventId,
                :bindingState, NULL, :observedAt, NULL, :finalizedAt, :observedAt, :synthetic
            ) ON CONFLICT (reservation_id) DO NOTHING
        """

        private const val FINALIZE_CANDIDATES_SQL = """
            SELECT * FROM domestic_delegated_spend_bindings
            WHERE binding_state = :pending AND observed_at < :cutoff
            ORDER BY observed_at, reservation_id
            LIMIT :batchLimit
            FOR UPDATE SKIP LOCKED
        """
    }
}
