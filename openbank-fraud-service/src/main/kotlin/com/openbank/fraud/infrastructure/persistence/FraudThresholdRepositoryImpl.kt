// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.application.port.out.FraudThresholdRepository
import com.openbank.fraud.application.port.out.ThresholdSetProposal
import com.openbank.fraud.domain.rules.FraudThresholdSet
import com.openbank.fraud.infrastructure.persistence.entity.FraudThresholdSetEntity
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Reactive Panache adapter for [FraudThresholdRepository] (ADR-0347). HQL names entity PROPERTIES
 * (`activeFrom`, not `active_from`) — the column spelling parses to the eye and fails at runtime
 * (#5913).
 */
@ApplicationScoped
class FraudThresholdRepositoryImpl(private val clock: Clock) :
    FraudThresholdRepository,
    PanacheRepository<FraudThresholdSetEntity> {

    @Inject lateinit var objectMapper: ObjectMapper

    override suspend fun findActive(at: Instant): FraudThresholdSet? = Panache.withSession {
        find(
            // Highest VERSION in force, not latest activeFrom: a lower version approved earlier with a
            // later effectiveFrom must never reactivate over a higher version already in force.
            "state = ?1 and activeFrom <= ?2 order by version desc",
            ProposalState.EXECUTED,
            at,
        ).firstResult()
    }.awaitSuspending()?.let { thresholdSetOf(it) }

    override suspend fun insert(proposal: ThresholdSetProposal) {
        val now = Instant.now(clock)
        val entity = FraudThresholdSetEntity().apply {
            id = proposal.id
            version = proposal.set.version
            state = proposal.state
            thresholdsJson = objectMapper.writeValueAsString(
                proposal.set.thresholds.mapValues { (_, byCcy) -> byCcy.mapValues { (_, a) -> a.toPlainString() } },
            )
            contentHash = proposal.contentHash
            effectiveFrom = proposal.effectiveFrom
            proposedBy = proposal.proposedBy
            proposedAt = proposal.proposedAt
            createdAt = now
            updatedAt = now
        }
        // A brand-new id: persist IS an insert here, which is exactly what is wanted.
        Panache.withTransaction { persist(entity) }.awaitSuspending()
    }

    override suspend fun findById(id: UUID): ThresholdSetProposal? = Panache.withSession {
        find("id = ?1", id).firstResult()
    }.awaitSuspending()?.let { toProposal(it) }

    override suspend fun findByState(state: ProposalState): List<ThresholdSetProposal> = Panache.withSession {
        find("state = ?1 order by proposedAt", state).list()
    }.awaitSuspending().map { toProposal(it) }

    override suspend fun maxLiveVersion(): Int? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createSelectionQuery(MAX_LIVE_VERSION_HQL, Int::class.javaObjectType)
                .setParameter("live", LIVE_STATES)
                .singleResultOrNull
        }
    }.awaitSuspending()

    override suspend fun compareAndSetDecision(decided: ThresholdSetProposal): Int = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createMutationQuery(DECIDE_HQL)
                .setParameter("state", decided.state)
                .setParameter("decidedBy", decided.decidedBy)
                .setParameter("decidedAt", decided.decidedAt)
                .setParameter("reason", decided.decisionReason)
                .setParameter("activeFrom", decided.activeFrom)
                .setParameter("updatedAt", Instant.now(clock))
                .setParameter("id", decided.id)
                .setParameter("from", ProposalState.PROPOSED)
                .executeUpdate()
        }
    }.awaitSuspending()

    private fun thresholdSetOf(entity: FraudThresholdSetEntity): FraudThresholdSet {
        val raw: Map<String, Map<String, String>> = objectMapper.readValue(entity.thresholdsJson, PAYLOAD_TYPE)
        return FraudThresholdSet(
            version = entity.version,
            thresholds = raw.mapValues { (_, byCcy) -> byCcy.mapValues { (_, amount) -> BigDecimal(amount) } },
        )
    }

    private fun toProposal(entity: FraudThresholdSetEntity) = ThresholdSetProposal(
        id = entity.id,
        set = thresholdSetOf(entity),
        contentHash = entity.contentHash,
        effectiveFrom = entity.effectiveFrom,
        state = entity.state,
        proposedBy = entity.proposedBy,
        proposedAt = entity.proposedAt,
        decidedBy = entity.decidedBy,
        decidedAt = entity.decidedAt,
        decisionReason = entity.decisionReason,
        activeFrom = entity.activeFrom,
    )

    private companion object {
        val PAYLOAD_TYPE = object : TypeReference<Map<String, Map<String, String>>>() {}

        val LIVE_STATES = listOf(ProposalState.PROPOSED, ProposalState.APPROVED, ProposalState.EXECUTED)

        const val MAX_LIVE_VERSION_HQL =
            "select max(t.version) from FraudThresholdSetEntity t where t.state in :live"

        /** `and state = :from` is the whole point: exactly one concurrent decider claims the row. */
        const val DECIDE_HQL =
            "update FraudThresholdSetEntity " +
                "set state = :state, decidedBy = :decidedBy, decidedAt = :decidedAt, " +
                "decisionReason = :reason, activeFrom = :activeFrom, updatedAt = :updatedAt " +
                "where id = :id and state = :from"
    }
}
