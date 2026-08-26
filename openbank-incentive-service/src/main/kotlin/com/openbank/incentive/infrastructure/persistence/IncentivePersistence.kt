// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.persistence

import com.openbank.incentive.application.IncentiveStore
import com.openbank.incentive.domain.CodeDigest
import com.openbank.incentive.domain.IncentiveConflict
import com.openbank.incentive.domain.IncentiveNotFound
import com.openbank.incentive.domain.IncentiveOffer
import com.openbank.incentive.domain.OfferRef
import com.openbank.incentive.domain.OfferStatus
import com.openbank.incentive.domain.PromoReservation
import com.openbank.incentive.domain.ReservationStatus
import com.openbank.incentive.domain.StackingPolicy
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
import java.time.Instant
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
}

@ApplicationScoped class OfferEntities : PanacheRepository<OfferEntity>

@ApplicationScoped class CodeEntities : PanacheRepository<CodeEntity>

@ApplicationScoped class ReservationEntities : PanacheRepository<ReservationEntity>

@ApplicationScoped class AuditEntities : PanacheRepository<AuditEntity>

@ApplicationScoped class OutboxEntities : PanacheRepository<OutboxEntity>

@ApplicationScoped
@Suppress("TooManyFunctions")
class PanacheIncentiveStore(
    private val offers: OfferEntities,
    private val codes: CodeEntities,
    private val reservations: ReservationEntities,
    private val audits: AuditEntities,
    private val outbox: OutboxEntities,
) : IncentiveStore {
    override suspend fun createOffer(offer: IncentiveOffer): IncentiveOffer = Panache.withTransaction {
        offers.persist(offer.toEntity()).flatMap { evidence(offer.ref.id, "incentive.offer.created.v1", offer.maker) }
            .replaceWith(offer)
    }.awaitSuspending()

    override suspend fun findOffer(id: UUID): IncentiveOffer? =
        Panache.withSession { offers.find("id", id).firstResult<OfferEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun submitOffer(id: UUID, actor: String): IncentiveOffer = Panache.withTransaction {
        lockedOffer(id).flatMap { entity ->
            val current = entity?.toDomain() ?: throw IncentiveNotFound("offer not found")
            val submitted = current.submit(actor)
            entity.status = submitted.status.name
            evidence(id, "incentive.offer.submitted.v1", actor).replaceWith(submitted)
        }
    }.awaitSuspending()

    override suspend fun publishOffer(id: UUID, actor: String, at: Instant): IncentiveOffer = Panache.withTransaction {
        lockedOffer(id).flatMap { entity ->
            val current = entity?.toDomain() ?: throw IncentiveNotFound("offer not found")
            val published = current.publish(actor)
            entity.status = published.status.name
            entity.checker = actor
            entity.publishedAt = at
            evidence(id, "incentive.offer.published.v1", actor).replaceWith(published)
        }
    }.awaitSuspending()

    override suspend fun addCodes(offerId: UUID, digests: Set<CodeDigest>, at: Instant): Int = Panache.withTransaction {
        lockedOffer(offerId).flatMap { offer ->
            if (offer == null) throw IncentiveNotFound("offer not found")
            if (offer.status == OfferStatus.PUBLISHED.name) throw IncentiveConflict("published inventory is immutable")
            val entities = digests.map { digest ->
                CodeEntity().apply {
                    this.digest = digest.value
                    this.offerId = offerId
                    status = "AVAILABLE"
                    createdAt = at
                    retainedUntil = offer.expiresAt.plusSeconds(RETENTION_SECONDS)
                }
            }
            codes.persist(entities).flatMap { evidence(offerId, "incentive.codes.imported.v1", "inventory") }
                .replaceWith(entities.size)
        }
    }.awaitSuspending()

    private companion object {
        const val RETENTION_SECONDS = 31_536_000L
    }

    override suspend fun reserve(
        offerId: UUID,
        digest: CodeDigest,
        partyRef: String,
        productRef: String,
        idempotencyKey: String,
        now: Instant,
        expiresAt: Instant,
    ): PromoReservation = Panache.withTransaction {
        lockedOffer(offerId).flatMap { offerEntity ->
            reservations.find("idempotencyKey", idempotencyKey).firstResult<ReservationEntity>().flatMap { replay ->
                if (replay != null) return@flatMap Uni.createFrom().item(replay.toDomain())
                val offer = offerEntity?.toDomain() ?: throw IncentiveNotFound("offer not found")
                if (!offer.accepts(productRef, now)) throw IncentiveConflict("offer is not redeemable")
                reservations.count("offerId = ?1 and status in (?2, ?3)", offerId, "RESERVED", "COMMITTED")
                    .flatMap { total ->
                        if (total >= offer.totalLimit) throw IncentiveConflict("offer total limit reached")
                        reservations.count(
                            "offerId = ?1 and partyRef = ?2 and status in (?3, ?4)",
                            offerId,
                            partyRef,
                            "RESERVED",
                            "COMMITTED",
                        )
                    }.flatMap { partyCount ->
                        if (partyCount >= offer.perPartyLimit) throw IncentiveConflict("party limit reached")
                        lockedCode(digest.value)
                    }.flatMap { code ->
                        if (code == null || code.offerId != offerId || code.status != "AVAILABLE") {
                            throw IncentiveConflict("code is unavailable")
                        }
                        code.status = "RESERVED"
                        val reservation = PromoReservation(
                            UUID.randomUUID(),
                            offer.ref,
                            digest,
                            partyRef,
                            productRef,
                            idempotencyKey,
                            now,
                            expiresAt,
                        )
                        reservations.persist(reservation.toEntity()).flatMap {
                            evidence(reservation.id, "incentive.reservation.created.v1", partyRef)
                        }.replaceWith(reservation)
                    }
            }
        }
    }.awaitSuspending()

    override suspend fun commit(id: UUID, actor: String, at: Instant): PromoReservation =
        transition(id, actor, at, ReservationStatus.COMMITTED)

    override suspend fun release(id: UUID, actor: String, at: Instant): PromoReservation =
        transition(id, actor, at, ReservationStatus.RELEASED)

    override suspend fun expireDue(at: Instant): Int = Panache.withTransaction {
        reservations.find("status = ?1 and expiresAt <= ?2", "RESERVED", at).list<ReservationEntity>().flatMap { due ->
            val expiration = due.fold(Uni.createFrom().voidItem()) { chain, entity ->
                chain.flatMap { expireEntity(entity, at) }
            }
            expiration.replaceWith(due.size)
        }
    }.awaitSuspending()

    private suspend fun transition(id: UUID, actor: String, at: Instant, target: ReservationStatus): PromoReservation =
        Panache.withTransaction {
            lockedReservation(id).flatMap { entity ->
                if (entity == null) throw IncentiveNotFound("reservation not found")
                val current = entity.toDomain()
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
                    evidence(
                        id,
                        "incentive.reservation.${target.name.lowercase()}.v1",
                        actor,
                    ).replaceWith(updated)
                }
            }
        }.awaitSuspending()

    private fun expireEntity(entity: ReservationEntity, at: Instant): Uni<Void> =
        lockedCode(entity.codeDigest).flatMap { code ->
            entity.status = ReservationStatus.EXPIRED.name
            entity.releasedAt = at
            requireNotNull(code).status = "AVAILABLE"
            evidence(entity.id, "incentive.reservation.expired.v1", "expiry")
        }

    private fun evidence(id: UUID, type: String, actor: String): Uni<Void> {
        val at = Instant.now()
        val audit = AuditEntity().apply {
            this.id = UUID.randomUUID()
            aggregateId = id
            eventType = type
            this.actor = actor
            occurredAt = at
            details = "{}"
        }
        val eventId = UUID.randomUUID()
        val event = OutboxEntity().apply {
            this.id = eventId
            aggregateId = id
            eventType = type
            payload =
                "{\"eventId\":\"$eventId\",\"aggregateId\":\"$id\"," +
                "\"eventType\":\"$type\",\"occurredAt\":\"$at\"}"
            occurredAt = at
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
    entity.idempotencyKey = idempotencyKey
    entity.status = status.name
    entity.reservedAt = reservedAt
    entity.expiresAt = expiresAt
}

private fun ReservationEntity.toDomain() = PromoReservation(
    id, OfferRef(offerId, offerName, offerVersion), CodeDigest(codeDigest), partyRef, productRef,
    idempotencyKey, reservedAt, expiresAt, ReservationStatus.valueOf(status),
)
