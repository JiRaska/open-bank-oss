package com.openbank.referral.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import com.openbank.referral.application.ReferralService
import com.openbank.referral.application.port.out.ReferralAuditRepository
import com.openbank.referral.application.port.out.ReferralInviteRepository
import com.openbank.referral.application.port.out.ReferralProgramRepository
import com.openbank.referral.application.port.out.ReferralRewardRepository
import com.openbank.referral.domain.InviteStatus
import com.openbank.referral.domain.ProgramStatus
import com.openbank.referral.domain.ReferralConflictException
import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralProgram
import com.openbank.referral.domain.ReferralReward
import com.openbank.referral.domain.RewardStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

@Entity
@Table(name = "referral_program")
class ReferralProgramEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var name: String
    var version = 1
    lateinit var rewardAmount: BigDecimal
    lateinit var currency: String
    lateinit var qualifyingEvent: String
    lateinit var attributionWindowEndsAt: Instant
    lateinit var status: String
    lateinit var maker: String
    var checker: String? = null
    lateinit var createdAt: Instant
    var publishedAt: Instant? = null
}

@Entity
@Table(name = "referral_invite")
class ReferralInviteEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var programId: UUID
    lateinit var tokenHash: String
    lateinit var referrerPartyId: UUID
    var refereePartyId: UUID? = null
    lateinit var status: String
    lateinit var expiresAt: Instant
    lateinit var idempotencyKey: String
    var attributedAt: Instant? = null
}

@Entity
@Table(name = "referral_reward")
class ReferralRewardEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var inviteId: UUID
    lateinit var programId: UUID
    lateinit var referrerPartyId: UUID
    lateinit var refereePartyId: UUID
    lateinit var qualificationEventId: String
    lateinit var rewardReference: String
    lateinit var amount: BigDecimal
    lateinit var currency: String
    lateinit var status: String
    lateinit var createdAt: Instant
    var requestedAt: Instant? = null
    var rewardedAt: Instant? = null
}

@Entity
@Table(name = "referral_audit_event")
class ReferralAuditEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var type: String
    lateinit var aggregateId: UUID
    lateinit var actor: String
    lateinit var details: String
    lateinit var occurredAt: Instant
}

@Entity
@Table(name = "referral_outbox")
class ReferralOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}

private fun ReferralProgramEntity.toDomain() = ReferralProgram(
    id, name, version, rewardAmount, currency, qualifyingEvent, attributionWindowEndsAt,
    ProgramStatus.valueOf(
        status,
    ),
    maker, checker, createdAt, publishedAt,
)
private fun ReferralInviteEntity.toDomain(token: String) = ReferralInvite(
    id, programId, token, referrerPartyId, refereePartyId,
    InviteStatus.valueOf(
        status,
    ),
    expiresAt, idempotencyKey, attributedAt,
)
private fun ReferralRewardEntity.toDomain() = ReferralReward(
    id, inviteId, programId, referrerPartyId, refereePartyId, qualificationEventId, rewardReference, amount, currency,
    RewardStatus.valueOf(
        status,
    ),
    createdAt, requestedAt, rewardedAt,
)

@ApplicationScoped class PanacheReferralProgramRepository :
    ReferralProgramRepository,
    PanacheRepository<ReferralProgramEntity> {
    override suspend fun create(p: ReferralProgram) = Panache.withTransaction {
        persist(
            ReferralProgramEntity().apply {
                id =
                    p.id
                name = p.name
                version = p.version
                rewardAmount = p.rewardAmount
                currency = p.currency
                qualifyingEvent =
                    p.qualifyingEvent
                attributionWindowEndsAt = p.attributionWindowEndsAt
                status = p.status.name
                maker = p.maker
                createdAt =
                    p.createdAt
            },
        )
    }.awaitSuspending().let { p }
    override suspend fun find(id: UUID) =
        Panache.withSession { find("id", id).firstResult<ReferralProgramEntity>() }.awaitSuspending()?.toDomain()
    override suspend fun listPublished() = Panache.withSession {
        find("status = ?1 order by publishedAt desc, name, version desc", ProgramStatus.PUBLISHED.name)
            .list<ReferralProgramEntity>()
    }.awaitSuspending().map { it.toDomain() }
    override suspend fun publish(id: UUID, maker: String, checker: String, at: Instant) = Panache.withTransaction {
        find("id", id).firstResult<ReferralProgramEntity>().map { e ->
            requireNotNull(e)
            if (e.status != ProgramStatus.DRAFT.name) {
                throw ReferralConflictException("program is not a draft")
            }
            if (e.maker == checker) {
                throw ReferralConflictException("maker cannot publish their own program")
            }
            e.status =
                ProgramStatus.PUBLISHED.name
            e.checker = checker
            e.publishedAt = at
            e.toDomain()
        }
    }.awaitSuspending()
}

@ApplicationScoped class PanacheReferralInviteRepository :
    ReferralInviteRepository,
    PanacheRepository<ReferralInviteEntity> {
    override suspend fun create(i: ReferralInvite) = Panache.withTransaction {
        persist(
            ReferralInviteEntity().apply {
                id =
                    i.id
                programId = i.programId
                tokenHash = ReferralService.hash(i.token)
                referrerPartyId = i.referrerPartyId
                status =
                    i.status.name
                expiresAt = i.expiresAt
                idempotencyKey = i.idempotencyKey
            },
        )
    }.awaitSuspending().let { i }
    override suspend fun findByToken(h: String) =
        Panache.withSession { find("tokenHash", h).firstResult<ReferralInviteEntity>() }.awaitSuspending()?.toDomain(h)
    override suspend fun findByIdempotencyKey(k: String) = Panache.withSession {
        find("idempotencyKey", k).firstResult<ReferralInviteEntity>()
    }.awaitSuspending()?.toDomain("stored")
    override suspend fun attribute(id: UUID, refereePartyId: UUID, at: Instant) = Panache.withTransaction {
        find("id = ?1 and status = ?2", id, InviteStatus.ISSUED.name).firstResult<ReferralInviteEntity>().map { e ->
            requireNotNull(e)
            e.status =
                InviteStatus.ATTRIBUTED.name
            e.refereePartyId = refereePartyId
            e.attributedAt = at
            e.toDomain(e.tokenHash)
        }
    }.awaitSuspending()
}

@ApplicationScoped class PanacheReferralRewardRepository(private val objectMapper: ObjectMapper) :
    ReferralRewardRepository,
    PanacheRepository<ReferralRewardEntity> {
    override suspend fun findByInviteAndEvent(i: UUID, e: String) = Panache.withSession {
        find("inviteId = ?1 and qualificationEventId = ?2", i, e).firstResult<ReferralRewardEntity>()
    }.awaitSuspending()?.toDomain()
    override suspend fun findByReference(r: String) = Panache.withSession {
        find(
            "rewardReference",
            r,
        ).firstResult<ReferralRewardEntity>()
    }.awaitSuspending()?.toDomain()
    override suspend fun create(r: ReferralReward, events: List<ReferralEvent>) = Panache.withTransaction {
        val rewardEntity = ReferralRewardEntity().apply {
            id =
                r.id
            inviteId = r.inviteId
            programId = r.programId
            referrerPartyId = r.referrerPartyId
            refereePartyId = r.refereePartyId
            qualificationEventId =
                r.qualificationEventId
            rewardReference = r.rewardReference
            amount = r.amount
            currency = r.currency
            status =
                r.status.name
            createdAt = r.createdAt
            requestedAt = r.requestedAt
        }
        Panache.getSession().chain { session ->
            events.fold(session.persist(rewardEntity)) { persisted, event ->
                persisted.call(Supplier { session.persist(event.toOutbox(r.id)) })
            }.replaceWith(r)
        }
    }.awaitSuspending()
    override suspend fun outcome(ref: String, status: String, at: Instant, event: ReferralEvent) =
        Panache.withTransaction {
            find("rewardReference", ref).firstResult<ReferralRewardEntity>().chain { e ->
                requireNotNull(e)
                e.status =
                    status
                if (status == RewardStatus.REWARDED.name)e.rewardedAt = at
                val updated = e.toDomain()
                Panache.getSession().chain { session ->
                    session.persist(event.toOutbox(updated.id)).replaceWith(updated)
                }
            }
        }.awaitSuspending()

    private fun ReferralEvent.toOutbox(aggregateId: UUID): ReferralOutboxEntity {
        val message = OutboxMessage(
            eventId = eventId,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = objectMapper.writeValueAsString(this),
            createdAt = occurredAt,
        )
        return ReferralOutboxEntity().also {
            it.eventId = message.eventId
            it.aggregateId = message.aggregateId
            it.eventType = message.eventType
            it.payload = message.payload
            it.status = OutboxStatus.PENDING.name
            it.synthetic = message.synthetic
            it.createdAt = message.createdAt
            it.updatedAt = message.createdAt
        }
    }
}

@ApplicationScoped class PanacheReferralAuditRepository :
    ReferralAuditRepository,
    PanacheRepository<ReferralAuditEntity> {
    override suspend fun append(t: String, a: UUID, actor: String, d: String, at: Instant) {
        Panache.withTransaction {
            persist(
                ReferralAuditEntity().apply {
                    id =
                        Ids.newId()
                    type = t
                    aggregateId = a
                    this.actor = actor
                    details = d
                    occurredAt = at
                },
            )
        }.awaitSuspending()
    }
}
