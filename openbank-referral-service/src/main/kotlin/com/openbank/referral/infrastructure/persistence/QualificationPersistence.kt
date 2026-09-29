// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.infrastructure.persistence

import com.openbank.referral.application.port.out.RecordedFact
import com.openbank.referral.application.port.out.ReferralQualifyingFactRepository
import com.openbank.referral.application.port.out.ReferralRefereeLookup
import com.openbank.referral.domain.InviteStatus
import com.openbank.referral.domain.QualifyingFact
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralReward
import com.openbank.referral.domain.RewardStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Column names follow this service's CamelCaseToUnderscoresNamingStrategy (application.yaml). */
@Entity
@Table(name = "referral_qualifying_fact")
class ReferralQualifyingFactEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var partyId: UUID
    lateinit var eventName: String
    lateinit var eventId: String
    var sourceRef: String? = null
    lateinit var occurredAt: Instant
    lateinit var recordedAt: Instant

    fun toDomain() = QualifyingFact(id, partyId, eventName, eventId, sourceRef, occurredAt, recordedAt)
}

/**
 * Insert-if-absent is Postgres's job (`ON CONFLICT DO NOTHING` over both unique constraints), not a
 * read-then-write in the consumer: two deliveries of the same event racing on two pods cannot both
 * insert, and neither sees an exception for the one that lost.
 */
@ApplicationScoped
class PanacheReferralQualifyingFactRepository : ReferralQualifyingFactRepository {
    override suspend fun recordIfAbsent(fact: QualifyingFact): RecordedFact {
        val inserted = Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(
                    "INSERT INTO referral_qualifying_fact " +
                        "(id, party_id, event_name, event_id, source_ref, occurred_at, recorded_at) " +
                        "VALUES (:id, :partyId, :eventName, :eventId, :sourceRef, :occurredAt, :recordedAt) " +
                        "ON CONFLICT DO NOTHING",
                )
                    .setParameter("id", fact.id)
                    .setParameter("partyId", fact.partyId)
                    .setParameter("eventName", fact.eventName)
                    .setParameter("eventId", fact.eventId)
                    .setParameter("sourceRef", fact.sourceRef)
                    .setParameter("occurredAt", fact.occurredAt)
                    .setParameter("recordedAt", fact.recordedAt)
                    .executeUpdate()
            }
        }.awaitSuspending() == 1
        if (inserted) return RecordedFact(fact, inserted = true)
        val stored = find(fact.partyId, fact.eventName) ?: findByEventId(fact.eventId)
        return RecordedFact(
            checkNotNull(stored) {
                "fact ${fact.eventId} conflicted but no stored row was found"
            },
            false,
        )
    }

    override suspend fun find(partyId: UUID, eventName: String): QualifyingFact? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "from ReferralQualifyingFactEntity f where f.partyId = :partyId and f.eventName = :eventName",
                ReferralQualifyingFactEntity::class.java,
            ).setParameter("partyId", partyId).setParameter("eventName", eventName).singleResultOrNull
        }
    }.awaitSuspending()?.toDomain()

    private suspend fun findByEventId(eventId: String): QualifyingFact? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "from ReferralQualifyingFactEntity f where f.eventId = :eventId",
                ReferralQualifyingFactEntity::class.java,
            ).setParameter("eventId", eventId).singleResultOrNull
        }
    }.awaitSuspending()?.toDomain()
}

/**
 * Referee-keyed reads. Mapped here rather than through the token-keyed repositories so this change
 * does not reshape `ReferralPersistence.kt`, which the outbox relay (#8859) also rewrites.
 */
@ApplicationScoped
class PanacheReferralRefereeLookup : ReferralRefereeLookup {
    override suspend fun attributedInvites(refereePartyId: UUID): List<ReferralInvite> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "from ReferralInviteEntity i where i.refereePartyId = :referee and i.status = :status",
                ReferralInviteEntity::class.java,
            ).setParameter("referee", refereePartyId).setParameter("status", InviteStatus.ATTRIBUTED.name).resultList
        }
    }.awaitSuspending().map { e ->
        ReferralInvite(
            id = e.id,
            programId = e.programId,
            token = e.tokenHash,
            referrerPartyId = e.referrerPartyId,
            refereePartyId = e.refereePartyId,
            status = InviteStatus.valueOf(e.status),
            expiresAt = e.expiresAt,
            idempotencyKey = e.idempotencyKey,
            attributedAt = e.attributedAt,
        )
    }

    override suspend fun rewardForReferee(refereePartyId: UUID, programId: UUID): ReferralReward? =
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "from ReferralRewardEntity r where r.refereePartyId = :referee and r.programId = :program",
                    ReferralRewardEntity::class.java,
                ).setParameter("referee", refereePartyId).setParameter("program", programId).singleResultOrNull
            }
        }.awaitSuspending()?.let { e ->
            ReferralReward(
                id = e.id,
                inviteId = e.inviteId,
                programId = e.programId,
                referrerPartyId = e.referrerPartyId,
                refereePartyId = e.refereePartyId,
                qualificationEventId = e.qualificationEventId,
                rewardReference = e.rewardReference,
                amount = e.amount,
                currency = e.currency,
                status = RewardStatus.valueOf(e.status),
                createdAt = e.createdAt,
                requestedAt = e.requestedAt,
                rewardedAt = e.rewardedAt,
            )
        }
}
