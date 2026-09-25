// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fraud.application.port.out.FraudInvestigationCaseStore
import com.openbank.fraud.domain.model.FraudInvestigationCase
import com.openbank.fraud.domain.model.FraudInvestigationStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fraud_investigation_cases")
class FraudInvestigationCaseEntity {
    @Id
    @Column(name = "case_id")
    lateinit var caseId: UUID

    @Column(name = "score_id")
    lateinit var scoreId: UUID

    @Column(name = "account_id")
    lateinit var accountId: UUID

    @Column(name = "counterparty_id")
    var counterpartyId: UUID? = null

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "revision")
    var revision: Long = 0

    @Column(name = "opened_by")
    lateinit var openedBy: String

    @Column(name = "opened_at")
    lateinit var openedAt: Instant

    @Column(name = "closed_by")
    var closedBy: String? = null

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    fun toDomain() = FraudInvestigationCase(
        caseId,
        scoreId,
        accountId,
        counterpartyId,
        FraudInvestigationStatus.valueOf(status),
        revision,
        openedBy,
        openedAt,
        closedBy,
        closedAt,
    )
}

/** Source-controlled case lifecycle; score is a lead and never creates a case by itself. */
@ApplicationScoped
class FraudInvestigationCaseRepository(
    private val clock: Clock,
    private val outbox: FraudOutboxRepositoryImpl,
    private val objectMapper: ObjectMapper,
) : FraudInvestigationCaseStore {
    override suspend fun find(caseId: UUID): FraudInvestigationCase? = Panache.withSession {
        Panache.getSession().flatMap { it.find(FraudInvestigationCaseEntity::class.java, caseId) }
    }.awaitSuspending()?.toDomain()

    override suspend fun matchingAssigned(caseId: UUID, candidateIds: List<UUID>): List<UUID> {
        if (candidateIds.isEmpty()) return emptyList()
        return Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.find(FraudInvestigationCaseEntity::class.java, caseId).flatMap { root ->
                    if (root == null || root.status != FraudInvestigationStatus.OPEN.name) {
                        Uni.createFrom().item(emptyList())
                    } else {
                        val query = if (root.counterpartyId == null) {
                            """select c.caseId from FraudInvestigationCaseEntity c
                               where c.caseId in :candidates and c.status = :open
                                 and c.accountId = :account order by c.caseId"""
                        } else {
                            """select c.caseId from FraudInvestigationCaseEntity c
                               where c.caseId in :candidates and c.status = :open
                                 and (c.accountId = :account or c.counterpartyId = :counterparty)
                               order by c.caseId"""
                        }
                        val matches = session.createQuery(query, UUID::class.java)
                            .setParameter("candidates", candidateIds)
                            .setParameter("open", FraudInvestigationStatus.OPEN.name)
                            .setParameter("account", root.accountId)
                        if (root.counterpartyId != null) matches.setParameter("counterparty", root.counterpartyId)
                        matches.setMaxResults(MAX_MATCHES + 1).resultList
                    }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun open(scoreId: UUID, actorId: String): FraudInvestigationCase? {
        require(actorId.isNotBlank()) { "opening actor is required" }
        val now = Instant.now(clock)
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "from FraudScoreEntity where scoreId = :scoreId",
                    FraudScoreEntity::class.java,
                ).setParameter("scoreId", scoreId).singleResultOrNull.flatMap { score ->
                    if (score == null || score.verdict != "REVIEW" || score.accountId == null) {
                        Uni.createFrom().nullItem<FraudInvestigationCase>()
                    } else {
                        val caseId = Ids.newId()
                        session.createNativeMutationQuery(
                            """INSERT INTO fraud_investigation_cases
                               (case_id, score_id, account_id, counterparty_id, status, revision, opened_by, opened_at)
                               VALUES (:caseId, :scoreId, :accountId, :counterpartyId, 'OPEN', 1, :actor, :at)
                               ON CONFLICT (score_id) DO NOTHING
                            """.trimIndent(),
                        ).setParameter("caseId", caseId).setParameter("scoreId", scoreId)
                            .setParameter("accountId", score.accountId)
                            .setParameter("counterpartyId", score.counterpartyId)
                            .setParameter("actor", actorId).setParameter("at", now)
                            .executeUpdate().flatMap { inserted ->
                                session.createQuery(
                                    "from FraudInvestigationCaseEntity where scoreId = :scoreId",
                                    FraudInvestigationCaseEntity::class.java,
                                ).setParameter("scoreId", scoreId).singleResult.flatMap { row ->
                                    val result = row.toDomain()
                                    if (inserted == 1) {
                                        outbox.persistInTransaction(
                                            reference(result, FraudCaseOpenedReference.EVENT_TYPE),
                                        )
                                            .replaceWith(result)
                                    } else {
                                        Uni.createFrom().item(result)
                                    }
                                }
                            }
                    }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun closeWithoutFinding(caseId: UUID, actorId: String): FraudInvestigationCase? {
        require(actorId.isNotBlank()) { "closing actor is required" }
        val now = Instant.now(clock)
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.find(FraudInvestigationCaseEntity::class.java, caseId).flatMap { row ->
                    if (row == null) {
                        Uni.createFrom().nullItem<FraudInvestigationCase>()
                    } else if (row.status == FraudInvestigationStatus.CLOSED_NO_FINDING.name) {
                        Uni.createFrom().item(row.toDomain())
                    } else {
                        val next = row.toDomain().closeWithoutFinding(actorId, now)
                        session.createNativeMutationQuery(
                            """UPDATE fraud_investigation_cases SET status = 'CLOSED_NO_FINDING',
                               revision = :nextRevision, closed_by = :actor, closed_at = :at
                               WHERE case_id = :caseId AND status = 'OPEN' AND revision = :previousRevision
                            """.trimIndent(),
                        ).setParameter("nextRevision", next.revision).setParameter("actor", actorId)
                            .setParameter("at", now).setParameter("caseId", caseId)
                            .setParameter("previousRevision", row.revision).executeUpdate().flatMap { changed ->
                                if (changed == 1) {
                                    outbox.persistInTransaction(reference(next, FraudCaseClosedReference.EVENT_TYPE))
                                        .replaceWith(next)
                                } else {
                                    // A competing close won the row update. PostgreSQL waits for that
                                    // transaction before reporting zero changed rows; refresh clears the
                                    // session's previously loaded OPEN entity before returning its result.
                                    session.refresh(row).replaceWith(row).map { current ->
                                        check(current.status == FraudInvestigationStatus.CLOSED_NO_FINDING.name) {
                                            "fraud case changed concurrently to an unexpected state"
                                        }
                                        current.toDomain()
                                    }
                                }
                            }
                    }
                }
            }
        }.awaitSuspending()
    }

    /** The Context topic carries a case reference only; all source identifiers remain in Fraud. */
    private fun reference(case: FraudInvestigationCase, eventType: String): OutboxMessage {
        val occurredAt = case.closedAt ?: case.openedAt
        val payload = when (eventType) {
            FraudCaseOpenedReference.EVENT_TYPE -> objectMapper.writeValueAsString(
                FraudCaseOpenedReference(eventType, case.id, case.revision, occurredAt),
            )
            FraudCaseClosedReference.EVENT_TYPE -> objectMapper.writeValueAsString(
                FraudCaseClosedReference(eventType, case.id, case.revision, occurredAt),
            )
            else -> error("unsupported fraud case reference: $eventType")
        }
        return OutboxMessage(
            eventId = Ids.newId(),
            aggregateId = case.id,
            eventType = eventType,
            payload = payload,
            createdAt = occurredAt,
        )
    }

    private companion object {
        const val MAX_MATCHES = 4
    }
}

/** Contract names are pinned independently from the case's internal status. */
data class FraudCaseOpenedReference(
    val eventType: String,
    val caseId: UUID,
    val revision: Long,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "fraud.case_opened"
    }
}

data class FraudCaseClosedReference(
    val eventType: String,
    val caseId: UUID,
    val revision: Long,
    val occurredAt: Instant,
) {
    companion object {
        const val EVENT_TYPE = "fraud.case_closed"
    }
}
