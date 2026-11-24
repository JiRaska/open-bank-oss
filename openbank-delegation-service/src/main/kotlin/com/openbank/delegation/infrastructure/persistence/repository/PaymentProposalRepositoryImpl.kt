// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.PaymentProposalRepository
import com.openbank.delegation.application.port.out.ProposalCreateOutcome
import com.openbank.delegation.application.port.out.ProposalLimits
import com.openbank.delegation.application.port.out.openDuplicateOf
import com.openbank.delegation.domain.event.PaymentProposalEvents
import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalNotPermittedException
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalStatus
import com.openbank.delegation.infrastructure.persistence.entity.PaymentProposalEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.OffsetDateTime
import java.util.UUID

/**
 * V38 adapter. Every state change and its lifecycle outbox row commit in ONE transaction, and every
 * transition is a revision compare-and-set on a row locked `FOR UPDATE`, so two concurrent approvals
 * of one proposal yield exactly one APPROVED and one refusal.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one aggregate adapter keeps every proposal write and its outbox row together
class PaymentProposalRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : PaymentProposalRepository {

    override suspend fun create(
        candidate: PaymentProposal,
        limits: ProposalLimits,
        now: OffsetDateTime,
    ): ProposalCreateOutcome = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            // Serialise every create for ONE party, so the limits and the dedupe below are a
            // decision about a stable set rather than a race between two concurrent tool calls.
            session.createNativeQuery<Any>(PARTY_LOCK_SQL)
                .setParameter("party", candidate.partyId.toString())
                .singleResult
                .flatMap { requireActiveGrant(session, candidate.grantId) }
                .flatMap { loadParty(session, candidate.partyId, PARTY_SCAN_LIMIT) }
                .flatMap { existing -> expireStale(session, existing, now) }
                .flatMap { current -> decideCreate(session, current, candidate, limits, now) }
        }
    }.awaitSuspending()

    /** The grant lock serializes proposal insertion with connection revocation. */
    private fun requireActiveGrant(session: Mutiny.Session, id: UUID): Uni<Void> =
        session.createNativeQuery<UUID>(LOCK_ACTIVE_GRANT_SQL)
            .setParameter("id", id)
            .resultList
            .flatMap { rows ->
                if (rows.isNotEmpty()) {
                    Uni.createFrom().voidItem()
                } else {
                    Uni.createFrom().failure(PaymentProposalNotPermittedException("proposal grant is no longer active"))
                }
            }

    private fun decideCreate(
        session: Mutiny.Session,
        current: List<PaymentProposal>,
        candidate: PaymentProposal,
        limits: ProposalLimits,
        now: OffsetDateTime,
    ): Uni<ProposalCreateOutcome> {
        current.openDuplicateOf(candidate, now)?.let {
            return Uni.createFrom().item(ProposalCreateOutcome.Deduplicated(it))
        }
        val open = current.count { it.isOpen(now) }
        if (open >= limits.maxOpenPerParty) {
            return Uni.createFrom().item(
                ProposalCreateOutcome.LimitReached("at most ${limits.maxOpenPerParty} open proposals per customer"),
            )
        }
        val dayAgo = now.minusDays(1)
        val createdToday = current.count { it.createdAt.isAfter(dayAgo) }
        if (createdToday >= limits.maxCreatedPerPartyPerDay) {
            return Uni.createFrom().item(
                ProposalCreateOutcome.LimitReached(
                    "at most ${limits.maxCreatedPerPartyPerDay} proposals per customer per day",
                ),
            )
        }
        return session.persist(PaymentProposalEntity.fromDomain(candidate))
            .flatMap { outboxRepository.persistInTransaction(outbox(candidate, now)) }
            .replaceWith(ProposalCreateOutcome.Created(candidate))
    }

    /** Persist EXPIRED (with its event) for this party's lapsed PENDING rows before deciding. */
    private fun expireStale(
        session: Mutiny.Session,
        existing: List<PaymentProposal>,
        now: OffsetDateTime,
    ): Uni<List<PaymentProposal>> {
        val stale = existing.filter {
            it.status == PaymentProposalStatus.PENDING && it.effectiveStatus(now) == PaymentProposalStatus.EXPIRED
        }
        var chain: Uni<Unit> = Uni.createFrom().item(Unit)
        stale.forEach { proposal ->
            chain = chain.flatMap { write(session, proposal, proposal.expire(now), now).replaceWith(Unit) }
        }
        return chain.map {
            existing.map { p -> if (p in stale) p.expire(now) else p }
        }
    }

    override suspend fun findById(id: UUID): PaymentProposal? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(FIND_BY_ID_SQL, PaymentProposalEntity::class.java)
                .setParameter("id", id)
                .resultList
                .map { rows -> rows.firstOrNull()?.toDomain() }
        }
    }.awaitSuspending()

    override suspend fun findByParty(partyId: UUID, limit: Int): List<PaymentProposal> = Panache.withSession {
        Panache.getSession().flatMap { session -> loadParty(session, partyId, limit) }
    }.awaitSuspending()

    override suspend fun transition(id: UUID, transition: (PaymentProposal) -> PaymentProposal): PaymentProposal? =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(LOCK_BY_ID_SQL, PaymentProposalEntity::class.java)
                    .setParameter("id", id)
                    .resultList
                    .flatMap { rows ->
                        val current = rows.firstOrNull()?.toDomain() ?: return@flatMap Uni.createFrom().nullItem()
                        val now = OffsetDateTime.now()
                        val next = transition(current)
                        write(session, current, next, now).replaceWith(next)
                    }
            }
        }.awaitSuspending()

    /**
     * Set-based on purpose: a grant revocation must NEVER be blocked by a proposal row. No row is
     * loaded through the domain (a legacy or malformed row would fail validation there and abort
     * the revoke); one UPDATE moves every PENDING row of the grant to REJECTED and RETURNING
     * supplies just the columns the lifecycle event carries.
     */
    override fun rejectPendingForGrant(grantId: UUID, now: OffsetDateTime): Uni<Void> =
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any?>>(REJECT_BY_GRANT_SQL)
                .setParameter("grant", grantId)
                .setParameter("now", now)
                .resultList
                .flatMap { rows ->
                    var chain: Uni<Void> = Uni.createFrom().voidItem()
                    rows.forEach { row ->
                        chain = chain.flatMap { outboxRepository.persistInTransaction(rejectedOutbox(row, now)) }
                    }
                    chain
                }
        }

    private fun rejectedOutbox(row: Array<Any?>, now: OffsetDateTime): OutboxMessage {
        val event = PaymentProposalEvents.forStatus(
            aggregateId = row[COL_ID] as UUID,
            partyId = row[COL_PARTY] as UUID,
            grantId = row[COL_GRANT] as UUID,
            mcpClientId = row[COL_CLIENT] as String,
            status = PaymentProposalStatus.REJECTED,
            revision = (row[COL_REVISION] as Number).toLong(),
            operationType = PaymentProposalOperationType.valueOf(row[COL_OPERATION] as String),
            amount = (row[COL_AMOUNT] as java.math.BigDecimal).toPlainString(),
            currency = row[COL_CURRENCY].toString(),
            payloadSha256 = row[COL_HASH].toString(),
            expiresAt = toOffset(row[COL_EXPIRES]),
            executedPaymentId = null,
            occurredAt = now.toInstant(),
        )
        return OutboxMessage(
            aggregateId = event.aggregateId,
            eventType = event.eventType,
            payload = objectMapper.writeValueAsString(event),
            createdAt = event.occurredAt,
        )
    }

    private fun toOffset(value: Any?): OffsetDateTime = when (value) {
        is OffsetDateTime -> value
        is java.time.Instant -> value.atOffset(java.time.ZoneOffset.UTC)
        is java.time.LocalDateTime -> value.atOffset(java.time.ZoneOffset.UTC)
        is java.sql.Timestamp -> value.toInstant().atOffset(java.time.ZoneOffset.UTC)
        else -> error("unexpected expires_at type ${value?.javaClass}")
    }

    private fun write(
        session: Mutiny.Session,
        current: PaymentProposal,
        next: PaymentProposal,
        now: OffsetDateTime,
    ): Uni<Void> {
        require(next.id == current.id && next.payloadSha256 == current.payloadSha256) {
            "a proposal transition cannot change its identity or instruction"
        }
        require(next.revision == current.revision + 1) { "a proposal transition advances the revision by one" }
        return session.createNativeQuery<Any>(UPDATE_SQL)
            .setParameter("status", next.status.name)
            .setParameter("revision", next.revision)
            .setParameter("decidedAt", next.decidedAt)
            .setParameter("paymentId", next.executedPaymentId)
            .setParameter("id", current.id)
            .setParameter("expectedRevision", current.revision)
            .executeUpdate()
            .flatMap { count ->
                if (count != 1) {
                    Uni.createFrom().failure(
                        IllegalStateException("payment proposal ${current.id} changed concurrently"),
                    )
                } else {
                    outboxRepository.persistInTransaction(outbox(next, now))
                }
            }
    }

    private fun loadParty(session: Mutiny.Session, partyId: UUID, limit: Int): Uni<List<PaymentProposal>> =
        session.createNativeQuery(FIND_BY_PARTY_SQL, PaymentProposalEntity::class.java)
            .setParameter("party", partyId)
            .setMaxResults(limit)
            .resultList
            .map { rows -> rows.map { it.toDomain() } }

    private fun outbox(proposal: PaymentProposal, now: OffsetDateTime): OutboxMessage {
        val event = PaymentProposalEvents.of(proposal, now.toInstant())
        return OutboxMessage(
            aggregateId = event.aggregateId,
            eventType = event.eventType,
            payload = objectMapper.writeValueAsString(event),
            createdAt = event.occurredAt,
        )
    }

    private companion object {
        // Column positions of REJECT_BY_GRANT_SQL's RETURNING list.
        const val COL_ID = 0
        const val COL_PARTY = 1
        const val COL_GRANT = 2
        const val COL_CLIENT = 3
        const val COL_REVISION = 4
        const val COL_OPERATION = 5
        const val COL_AMOUNT = 6
        const val COL_CURRENCY = 7
        const val COL_HASH = 8
        const val COL_EXPIRES = 9

        /** Enough to cover the daily limit and every open proposal; a party can never exceed it. */
        const val PARTY_SCAN_LIMIT = 200
        const val PARTY_LOCK_SQL =
            "select 1 from (select pg_advisory_xact_lock(hashtextextended('payment-proposal:' || :party, 0))) l"
        const val LOCK_ACTIVE_GRANT_SQL = """
            select id from delegation_grants
             where id = :id and status = 'ACTIVE' and valid_from <= statement_timestamp()
               and (valid_to is null or valid_to > statement_timestamp())
             for share
        """
        const val FIND_BY_ID_SQL = "select * from delegation_payment_proposals where id = :id"
        const val LOCK_BY_ID_SQL = "$FIND_BY_ID_SQL for update"
        const val REJECT_BY_GRANT_SQL = """
            update delegation_payment_proposals
               set status = 'REJECTED', revision = revision + 1, decided_at = :now
             where grant_id = :grant and status = 'PENDING'
         returning id, party_id, grant_id, mcp_client_id, revision, operation_type, amount, currency,
                   payload_sha256, expires_at
        """
        const val FIND_BY_PARTY_SQL =
            "select * from delegation_payment_proposals where party_id = :party order by created_at desc, id"
        const val UPDATE_SQL = """
            update delegation_payment_proposals
               set status = :status, revision = :revision, decided_at = :decidedAt, executed_payment_id = :paymentId
             where id = :id and revision = :expectedRevision
        """
    }
}
