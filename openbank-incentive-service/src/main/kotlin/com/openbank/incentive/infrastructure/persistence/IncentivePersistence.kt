// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.incentive.application.IncentiveStore
import com.openbank.incentive.application.ReserveIncentive
import com.openbank.incentive.domain.CodeDigest
import com.openbank.incentive.domain.IncentiveConflict
import com.openbank.incentive.domain.IncentiveNotFound
import com.openbank.incentive.domain.IncentiveOffer
import com.openbank.incentive.domain.OfferRef
import com.openbank.incentive.domain.OfferStatus
import com.openbank.incentive.domain.PromoReservation
import com.openbank.incentive.domain.ReservationStatus
import com.openbank.incentive.domain.StackingPolicy
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheQuery
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.LockMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(name = "incentive_offer")
class OfferEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var name: String
    var version: Int = 1
    lateinit var productScope: String
    lateinit var effectiveFrom: Instant
    lateinit var expiresAt: Instant
    var totalLimit: Int = 0
    var perPartyLimit: Int = 0
    lateinit var stackingPolicy: String
    lateinit var status: String
    lateinit var maker: String
    var checker: String? = null
    lateinit var createdAt: Instant
    var publishedAt: Instant? = null
}

@Entity
@Table(name = "promo_code_inventory")
class CodeEntity : PanacheEntityBase() {
    @Id lateinit var digest: String
    lateinit var offerId: UUID
    lateinit var status: String
    lateinit var createdAt: Instant
    lateinit var retainedUntil: Instant
}

@Entity
@Table(name = "promo_reservation")
class ReservationEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var offerId: UUID
    lateinit var offerName: String
    var offerVersion: Int = 1
    lateinit var codeDigest: String
    lateinit var partyRef: String
    lateinit var productRef: String
    var attributionRef: UUID? = null
    lateinit var idempotencyKey: String
    lateinit var status: String
    lateinit var reservedAt: Instant
    lateinit var expiresAt: Instant
    var committedAt: Instant? = null
    var releasedAt: Instant? = null
}

@Entity
@Table(name = "incentive_audit_event")
class AuditEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var aggregateId: UUID
    lateinit var eventType: String
    lateinit var actor: String
    lateinit var occurredAt: Instant
    lateinit var details: String
}

@Entity
@Table(name = "incentive_outbox")
class OutboxEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var aggregateId: UUID
    lateinit var eventType: String
    lateinit var payload: String
    lateinit var occurredAt: Instant
    var publishedAt: Instant? = null
    lateinit var status: String
    var attemptCount: Int = 0
    var claimedAt: Instant? = null
    var claimToken: UUID? = null
    lateinit var updatedAt: Instant
    var lastError: String? = null
    var synthetic: Boolean = false
}

@ApplicationScoped class OfferEntities : PanacheRepository<OfferEntity>

@ApplicationScoped class CodeEntities : PanacheRepository<CodeEntity>

@ApplicationScoped class ReservationEntities : PanacheRepository<ReservationEntity>

@ApplicationScoped class AuditEntities : PanacheRepository<AuditEntity>

@ApplicationScoped
class OutboxEntities :
    PanacheRepository<OutboxEntity>,
    OutboxRepository {
    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = listProcessableUni(limit).awaitSuspending()

    fun listProcessableUni(limit: Int): Uni<List<OutboxEntry>> = Panache.getSession().flatMap { session ->
        session.createQuery(
            "from OutboxEntity where status in (:pending, :failed) order by occurredAt asc",
            OutboxEntity::class.java,
        ).setParameter("pending", OutboxStatus.PENDING.name)
            .setParameter("failed", OutboxStatus.FAILED.name)
            .setMaxResults(limit.coerceAtLeast(1))
            .resultList
    }.map { rows -> rows.map { it.toEntry() } }

    override suspend fun countProcessable(): Long = Panache.withSession {
        count("status in (?1, ?2)", OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)
    }.awaitSuspending()

    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> =
        claimWithToken(limit, staleAfter).map {
            it.entry
        }

    data class ClaimedEntry(val entry: OutboxEntry, val token: UUID)

    suspend fun claimWithToken(limit: Int, staleAfter: Duration): List<ClaimedEntry> {
        val now = Instant.now()
        val token = Ids.newId()
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, OutboxEntity::class.java)
                    .setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("staleThreshold", now.minus(staleAfter))
                    .setParameter("claimLimit", limit.coerceAtLeast(1))
                    .setParameter("now", now)
                    .setParameter("claimToken", token)
                    .resultList
            }
        }.map { rows -> rows.map { ClaimedEntry(it.toEntry(), token) } }.awaitSuspending()
    }

    suspend fun markSentClaimed(claimed: ClaimedEntry, sentAt: Instant): Boolean = Panache.withTransaction {
        update(
            "status = ?1, attemptCount = attemptCount + 1, publishedAt = ?2, updatedAt = ?2, " +
                "lastError = null, claimToken = null where id = ?3 and status = ?4 and claimToken = ?5",
            OutboxStatus.SENT.name,
            sentAt,
            claimed.entry.eventId,
            OutboxStatus.DISPATCHING.name,
            claimed.token,
        ).map { it == 1 }
    }.awaitSuspending()

    suspend fun markFailedClaimed(claimed: ClaimedEntry, error: String, failedAt: Instant): OutboxStatus? =
        Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.find(OutboxEntity::class.java, claimed.entry.eventId, LockMode.PESSIMISTIC_WRITE)
            }.map { entity ->
                val current = entity ?: return@map null
                if (current.status != OutboxStatus.DISPATCHING.name || current.claimToken != claimed.token) {
                    return@map null
                }
                current.attemptCount += 1
                current.status = OutboxFailurePolicy.statusAfterFailure(current.attemptCount).name
                current.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                current.updatedAt = failedAt
                current.claimToken = null
                OutboxStatus.valueOf(current.status)
            }
        }.awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("id", eventId).firstResult<OutboxEntity>().invoke { row ->
                if (row != null) {
                    row.status = OutboxStatus.SENT.name
                    row.attemptCount += 1
                    row.publishedAt = sentAt
                    row.updatedAt = sentAt
                    row.lastError = null
                }
            }.replaceWithVoid()
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        Panache.withTransaction {
            find("id", eventId).firstResult<OutboxEntity>().map { row ->
                if (row == null) return@map OutboxStatus.FAILED
                row.attemptCount += 1
                row.status = OutboxFailurePolicy.statusAfterFailure(row.attemptCount).name
                row.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                row.updatedAt = failedAt
                OutboxStatus.valueOf(row.status)
            }
        }.awaitSuspending()

    private fun OutboxEntity.toEntry() = OutboxEntry(
        id, aggregateId, eventType, payload, OutboxStatus.valueOf(status), attemptCount,
        occurredAt, updatedAt, publishedAt, lastError, synthetic,
    )

    private companion object {
        @Suppress("MaxLineLength")
        const val CLAIM_SQL = """
            UPDATE incentive_outbox
            SET status = :dispatching, claimed_at = :now, claim_token = :claimToken, updated_at = :now
            WHERE id IN (
                SELECT id FROM incentive_outbox
                WHERE status IN (:pending, :failed)
                   OR (status = :dispatching AND claimed_at < :staleThreshold)
                ORDER BY occurred_at ASC
                LIMIT :claimLimit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
        """
    }
}

@ApplicationScoped
@Suppress("TooManyFunctions")
class PanacheIncentiveStore(
    private val offers: OfferEntities,
    private val codes: CodeEntities,
    private val reservations: ReservationEntities,
    private val audits: AuditEntities,
    private val outbox: OutboxEntities,
    private val objectMapper: ObjectMapper,
) : IncentiveStore {
    override suspend fun createOffer(offer: IncentiveOffer): IncentiveOffer = Panache.withTransaction {
        offers.persist(offer.toEntity()).flatMap {
            evidence(offer.ref.id, eventType = "incentive.offer.created.v1", actor = offer.maker)
        }
            .replaceWith(offer)
    }.awaitSuspending()

    override suspend fun findOffer(id: UUID): IncentiveOffer? =
        Panache.withSession { offers.find("id", id).firstResult<OfferEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun listPublishedOffers(): List<IncentiveOffer> = Panache.withSession {
        offers.list("status = ?1 order by name asc, version desc", OfferStatus.PUBLISHED.name)
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun submitOffer(id: UUID, actor: String): IncentiveOffer = Panache.withTransaction {
        lockedOffer(id).flatMap { entity ->
            val current = entity?.toDomain() ?: throw IncentiveNotFound("offer not found")
            val submitted = current.submit(actor)
            entity.status = submitted.status.name
            evidence(id, eventType = "incentive.offer.submitted.v1", actor = actor).replaceWith(submitted)
        }
    }.awaitSuspending()

    override suspend fun publishOffer(id: UUID, actor: String, at: Instant): IncentiveOffer = Panache.withTransaction {
        lockedOffer(id).flatMap { entity ->
            val current = entity?.toDomain() ?: throw IncentiveNotFound("offer not found")
            val published = current.publish(actor)
            entity.status = published.status.name
            entity.checker = actor
            entity.publishedAt = at
            evidence(id, eventType = "incentive.offer.published.v1", actor = actor).replaceWith(published)
        }
    }.awaitSuspending()

    override suspend fun addCodes(offerId: UUID, digests: Set<CodeDigest>, actor: String, at: Instant): Int =
        Panache.withTransaction {
            lockedOffer(offerId).flatMap { offer ->
                if (offer == null) throw IncentiveNotFound("offer not found")
                if (offer.status ==
                    OfferStatus.PUBLISHED.name
                ) {
                    throw IncentiveConflict("published inventory is immutable")
                }
                val entities = digests.map { digest ->
                    CodeEntity().apply {
                        this.digest = digest.value
                        this.offerId = offerId
                        status = "AVAILABLE"
                        createdAt = at
                        retainedUntil = offer.expiresAt.atZone(ZoneOffset.UTC).plusMonths(RETENTION_MONTHS).toInstant()
                    }
                }
                codes.persist(entities).flatMap {
                    evidence(offerId, eventType = "incentive.codes.imported.v1", actor = actor)
                }
                    .replaceWith(entities.size)
            }
        }.awaitSuspending()

    private companion object {
        const val RETENTION_MONTHS = 13L
        val ATTRIBUTED_FINALIZATION_GRACE: Duration = Duration.ofHours(24)
    }

    override suspend fun reserve(command: ReserveIncentive): PromoReservation = Panache.withTransaction {
        lockedOffer(command.offerId).flatMap { offerEntity ->
            reservations.find("offerId = ?1 and idempotencyKey = ?2", command.offerId, command.idempotencyKey)
                .firstResult<ReservationEntity>().flatMap { replay ->
                    if (replay != null) {
                        if (!replay.matches(command)) {
                            throw IncentiveConflict("idempotency key was already used for a different request")
                        }
                        return@flatMap Uni.createFrom().item(replay.toDomain())
                    }
                    existingAttribution(command.attributionRef).flatMap { attributed ->
                        if (attributed != null) throw IncentiveConflict("attribution was already reserved")
                        val offer = redeemableOffer(offerEntity, command)
                        reservations.count(
                            "offerId = ?1 and status in (?2, ?3)",
                            command.offerId,
                            "RESERVED",
                            "COMMITTED",
                        )
                            .flatMap { total ->
                                if (total >= offer.totalLimit) throw IncentiveConflict("offer total limit reached")
                                reservations.count(
                                    "offerId = ?1 and partyRef = ?2 and status in (?3, ?4)",
                                    command.offerId,
                                    command.partyRef,
                                    "RESERVED",
                                    "COMMITTED",
                                )
                            }.flatMap { partyCount ->
                                if (partyCount >= offer.perPartyLimit) throw IncentiveConflict("party limit reached")
                                lockedCode(command.digest.value)
                            }.flatMap { code ->
                                if (code == null || code.offerId != command.offerId || code.status != "AVAILABLE") {
                                    throw IncentiveConflict("code is unavailable")
                                }
                                code.status = "RESERVED"
                                val reservation = PromoReservation(
                                    Ids.newId(),
                                    offer.ref,
                                    command.digest,
                                    command.partyRef,
                                    command.productRef,
                                    command.idempotencyKey,
                                    command.now,
                                    command.expiresAt,
                                    command.attributionRef,
                                )
                                reservations.persist(reservation.toEntity()).flatMap {
                                    reservationEvidence(reservation, ReservationStatus.RESERVED, command.actor)
                                }.replaceWith(reservation)
                            }
                    }
                }
        }
    }.awaitSuspending()

    private fun redeemableOffer(entity: OfferEntity?, command: ReserveIncentive): IncentiveOffer {
        val offer = entity?.toDomain() ?: throw IncentiveNotFound("offer not found")
        if (!offer.accepts(command.productRef, command.now)) throw IncentiveConflict("offer is not redeemable")
        return offer
    }

    private fun existingAttribution(attributionRef: UUID?): Uni<ReservationEntity?> = if (attributionRef == null) {
        Uni.createFrom().nullItem()
    } else {
        reservations.find("attributionRef", attributionRef).firstResult()
    }

    override suspend fun commit(id: UUID, actor: String, at: Instant): PromoReservation =
        transition(id, actor, at, ReservationStatus.COMMITTED)

    override suspend fun release(id: UUID, actor: String, at: Instant): PromoReservation =
        transition(id, actor, at, ReservationStatus.RELEASED)

    override suspend fun commitAttributed(
        id: UUID,
        partyRef: String,
        productRef: String,
        actor: String,
        qualifiedAt: Instant,
    ): PromoReservation = transition(id, actor, qualifiedAt, ReservationStatus.COMMITTED, partyRef, productRef)

    override suspend fun releaseAttributed(
        id: UUID,
        partyRef: String,
        productRef: String,
        actor: String,
        at: Instant,
    ): PromoReservation = transition(id, actor, at, ReservationStatus.RELEASED, partyRef, productRef)

    override suspend fun expireDue(at: Instant): Int {
        val ids = Panache.withSession {
            reservations.find("status = ?1 and expiresAt <= ?2", "RESERVED", at)
                .list<ReservationEntity>()
                .map { candidates -> candidates.map { it.id } }
        }.awaitSuspending()
        var expired = 0
        ids.forEach { id -> expired += expireOne(id, at) }
        return expired
    }

    private suspend fun expireOne(id: UUID, at: Instant): Int = Panache.withTransaction {
        lockedReservation(id).flatMap { locked ->
            val finalizationDeadline = locked?.expiresAt?.let { expiry ->
                if (locked.attributionRef == null) expiry else expiry.plus(ATTRIBUTED_FINALIZATION_GRACE)
            }
            if (
                locked == null ||
                locked.status != ReservationStatus.RESERVED.name ||
                requireNotNull(finalizationDeadline).isAfter(at)
            ) {
                Uni.createFrom().item(0)
            } else {
                expireEntity(locked, at).replaceWith(1)
            }
        }
    }.awaitSuspending()

    private suspend fun transition(
        id: UUID,
        actor: String,
        at: Instant,
        target: ReservationStatus,
        expectedPartyRef: String? = null,
        expectedProductRef: String? = null,
    ): PromoReservation = Panache.withTransaction {
        lockedReservation(id).flatMap { entity ->
            if (entity == null) throw IncentiveNotFound("reservation not found")
            val current = entity.toDomain()
            requireAttributedOwner(current, expectedPartyRef, expectedProductRef)
            val updated = when (target) {
                ReservationStatus.COMMITTED -> current.commit(at)
                ReservationStatus.RELEASED -> current.release()
                else -> throw IllegalArgumentException("unsupported transition")
            }
            if (updated === current) return@flatMap Uni.createFrom().item(current)
            entity.status = target.name
            if (target == ReservationStatus.COMMITTED) entity.committedAt = at else entity.releasedAt = at
            lockedCode(entity.codeDigest).flatMap { code ->
                requireNotNull(code)
                code.status = if (target == ReservationStatus.COMMITTED) "REDEEMED" else "AVAILABLE"
                when (target) {
                    ReservationStatus.COMMITTED -> reservationEvidence(updated, target, actor)
                    ReservationStatus.RELEASED -> reservationEvidence(updated, target, actor)
                    else -> error("unsupported transition")
                }
                    .replaceWith(updated)
            }
        }
    }.awaitSuspending()

    private fun requireAttributedOwner(
        reservation: PromoReservation,
        expectedPartyRef: String?,
        expectedProductRef: String?,
    ) {
        if (expectedPartyRef == null) return
        val owned = reservation.attributionRef != null &&
            reservation.partyRef == expectedPartyRef &&
            reservation.productRef == expectedProductRef
        if (!owned) throw IncentiveNotFound("reservation not found")
    }

    private fun expireEntity(entity: ReservationEntity, at: Instant): Uni<Void> =
        lockedCode(entity.codeDigest).flatMap { code ->
            entity.status = ReservationStatus.EXPIRED.name
            entity.releasedAt = at
            requireNotNull(code).status = "AVAILABLE"
            reservationEvidence(
                entity.toDomain().copy(status = ReservationStatus.EXPIRED),
                ReservationStatus.EXPIRED,
                "expiry",
            )
        }

    private fun reservationEvidence(
        reservation: PromoReservation,
        status: ReservationStatus,
        actor: String,
    ): Uni<Void> {
        val attributionRef = reservation.attributionRef
        if (attributionRef == null) {
            return evidence(
                reservation.id,
                eventType = ReservationEventTypes.forStatus(status, attributed = false),
                actor = actor,
            )
        }
        val eventType = ReservationEventTypes.forStatus(status, attributed = true)
        val at = Instant.now()
        val audit = AuditEntity().apply {
            id = Ids.newId()
            aggregateId = reservation.id
            this.eventType = eventType
            this.actor = actor
            occurredAt = at
            details = "{}"
        }
        val eventId = Ids.newId()
        val payload = attributedEvidence(eventId, reservation, attributionRef, status, at)
        val event = OutboxEntity().apply {
            id = eventId
            aggregateId = reservation.id
            this.eventType = eventType
            this.payload = objectMapper.writeValueAsString(payload)
            occurredAt = at
            this.status = OutboxStatus.PENDING.name
            updatedAt = at
        }
        return audits.persist(audit).flatMap { outbox.persist(event) }.replaceWithVoid()
    }

    private fun evidence(id: UUID, eventType: String, actor: String): Uni<Void> {
        val at = Instant.now()
        val audit = AuditEntity().apply {
            this.id = Ids.newId()
            aggregateId = id
            this.eventType = eventType
            this.actor = actor
            occurredAt = at
            details = "{}"
        }
        val eventId = Ids.newId()
        val correlationId = id
        val event = OutboxEntity().apply {
            this.id = eventId
            aggregateId = id
            this.eventType = eventType
            payload =
                "{\"eventId\":\"$eventId\",\"correlationId\":\"$correlationId\",\"aggregateId\":\"$id\"," +
                "\"eventType\":\"$eventType\",\"occurredAt\":\"$at\"}"
            occurredAt = at
            status = OutboxStatus.PENDING.name
            updatedAt = at
        }
        return audits.persist(audit).flatMap { outbox.persist(event) }.replaceWithVoid()
    }

    private fun lockedOffer(id: UUID): Uni<OfferEntity?> {
        val query: PanacheQuery<OfferEntity> = offers.find("id", id)
        return query.withLock<OfferEntity>(LockModeType.PESSIMISTIC_WRITE).firstResult<OfferEntity>()
    }

    private fun lockedCode(digest: String): Uni<CodeEntity?> {
        val query: PanacheQuery<CodeEntity> = codes.find("digest", digest)
        return query.withLock<CodeEntity>(LockModeType.PESSIMISTIC_WRITE).firstResult<CodeEntity>()
    }

    private fun lockedReservation(id: UUID): Uni<ReservationEntity?> {
        val query: PanacheQuery<ReservationEntity> = reservations.find("id", id)
        return query.withLock<ReservationEntity>(LockModeType.PESSIMISTIC_WRITE).firstResult<ReservationEntity>()
    }
}

private fun IncentiveOffer.toEntity() = OfferEntity().also { entity ->
    entity.id = ref.id
    entity.name = ref.name
    entity.version = ref.version
    entity.productScope = productScope.joinToString("\u001f")
    entity.effectiveFrom = effectiveFrom
    entity.expiresAt = expiresAt
    entity.totalLimit = totalLimit
    entity.perPartyLimit = perPartyLimit
    entity.stackingPolicy = stackingPolicy.name
    entity.status = status.name
    entity.maker = maker
    entity.checker = checker
    entity.createdAt = Instant.now()
}

private fun OfferEntity.toDomain() = IncentiveOffer(
    OfferRef(id, name, version), productScope.split("\u001f").toSet(), effectiveFrom, expiresAt,
    totalLimit, perPartyLimit, StackingPolicy.valueOf(stackingPolicy), OfferStatus.valueOf(status), maker, checker,
)

private fun PromoReservation.toEntity() = ReservationEntity().also { entity ->
    entity.id = id
    entity.offerId = offerRef.id
    entity.codeDigest = codeDigest.value
    entity.offerName = offerRef.name
    entity.offerVersion = offerRef.version
    entity.partyRef = partyRef
    entity.productRef = productRef
    entity.attributionRef = attributionRef
    entity.idempotencyKey = idempotencyKey
    entity.status = status.name
    entity.reservedAt = reservedAt
    entity.expiresAt = expiresAt
}

private fun ReservationEntity.toDomain() = PromoReservation(
    id, OfferRef(offerId, offerName, offerVersion), CodeDigest(codeDigest), partyRef, productRef,
    idempotencyKey, reservedAt, expiresAt, attributionRef, ReservationStatus.valueOf(status),
)

private fun ReservationEntity.matches(command: ReserveIncentive): Boolean = codeDigest == command.digest.value &&
    partyRef == command.partyRef &&
    productRef == command.productRef &&
    attributionRef == command.attributionRef

private object ReservationEventTypes {
    const val CREATED_V1 = "incentive.reservation.created.v1"
    const val COMMITTED_V1 = "incentive.reservation.committed.v1"
    const val RELEASED_V1 = "incentive.reservation.released.v1"
    const val EXPIRED_V1 = "incentive.reservation.expired.v1"
    const val CREATED_V2 = "incentive.reservation.created.v2"
    const val COMMITTED_V2 = "incentive.reservation.committed.v2"
    const val RELEASED_V2 = "incentive.reservation.released.v2"
    const val EXPIRED_V2 = "incentive.reservation.expired.v2"

    fun forStatus(status: ReservationStatus, attributed: Boolean): String = when (status) {
        ReservationStatus.RESERVED -> if (attributed) CREATED_V2 else CREATED_V1
        ReservationStatus.COMMITTED -> if (attributed) COMMITTED_V2 else COMMITTED_V1
        ReservationStatus.RELEASED -> if (attributed) RELEASED_V2 else RELEASED_V1
        ReservationStatus.EXPIRED -> if (attributed) EXPIRED_V2 else EXPIRED_V1
    }
}

private fun attributedEvidence(
    eventId: UUID,
    reservation: PromoReservation,
    attributionRef: UUID,
    status: ReservationStatus,
    occurredAt: Instant,
): AttributedReservationEvidence = when (status) {
    ReservationStatus.RESERVED -> ReservationCreatedV2(
        eventId,
        reservation.id,
        reservation.id,
        occurredAt,
        reservation.id,
        reservation.offerRef,
        attributionRef,
        status,
    )
    ReservationStatus.COMMITTED -> ReservationCommittedV2(
        eventId,
        reservation.id,
        reservation.id,
        occurredAt,
        reservation.id,
        reservation.offerRef,
        attributionRef,
        status,
    )
    ReservationStatus.RELEASED -> ReservationReleasedV2(
        eventId,
        reservation.id,
        reservation.id,
        occurredAt,
        reservation.id,
        reservation.offerRef,
        attributionRef,
        status,
    )
    ReservationStatus.EXPIRED -> ReservationExpiredV2(
        eventId,
        reservation.id,
        reservation.id,
        occurredAt,
        reservation.id,
        reservation.offerRef,
        attributionRef,
        status,
    )
}

private sealed interface AttributedReservationEvidence

private data class ReservationCreatedV2(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val reservationId: UUID,
    val offerRef: OfferRef,
    val attributionRef: UUID,
    val status: ReservationStatus,
    val eventType: String = EVENT_TYPE,
) : AttributedReservationEvidence {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.created.v2"
    }
}

private data class ReservationCommittedV2(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val reservationId: UUID,
    val offerRef: OfferRef,
    val attributionRef: UUID,
    val status: ReservationStatus,
    val eventType: String = EVENT_TYPE,
) : AttributedReservationEvidence {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.committed.v2"
    }
}

private data class ReservationReleasedV2(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val reservationId: UUID,
    val offerRef: OfferRef,
    val attributionRef: UUID,
    val status: ReservationStatus,
    val eventType: String = EVENT_TYPE,
) : AttributedReservationEvidence {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.released.v2"
    }
}

private data class ReservationExpiredV2(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val reservationId: UUID,
    val offerRef: OfferRef,
    val attributionRef: UUID,
    val status: ReservationStatus,
    val eventType: String = EVENT_TYPE,
) : AttributedReservationEvidence {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.expired.v2"
    }
}

internal data class ReservationCreatedV1(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.created.v1"
    }
}

internal data class ReservationCommittedV1(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.committed.v1"
    }
}

internal data class ReservationReleasedV1(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.released.v1"
    }
}

internal data class ReservationExpiredV1(
    val eventId: UUID,
    val correlationId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "incentive.reservation.expired.v1"
    }
}
