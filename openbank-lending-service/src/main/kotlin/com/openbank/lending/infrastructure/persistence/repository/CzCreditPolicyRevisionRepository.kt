// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.CzCreditPolicyRevision
import com.openbank.lending.application.port.out.CzCreditPolicyStore
import com.openbank.lending.infrastructure.persistence.entity.CzCreditPolicyRevisionEntity
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@ApplicationScoped
class CzCreditPolicyRevisionRepository(private val sessions: Mutiny.SessionFactory) : CzCreditPolicyStore {
    override fun propose(row: CzCreditPolicyRevision): Uni<CzCreditPolicyRevision> =
        sessions.withTransaction { session ->
            session.createNativeMutationQuery(
                """
                INSERT INTO cz_credit_policy_revision
                    (id, revision, effective_from, payload, content_hash, state, proposed_by, proposed_at)
                VALUES (:id, :revision, :effectiveFrom, :payload, :hash, 'PROPOSED', :proposer, :proposedAt)
                ON CONFLICT (content_hash) DO NOTHING
                """.trimIndent(),
            )
                .setParameter("id", row.id)
                .setParameter("revision", row.revision)
                .setParameter("effectiveFrom", row.effectiveFrom)
                .setParameter("payload", row.payload)
                .setParameter("hash", row.contentHash)
                .setParameter("proposer", row.proposedBy)
                .setParameter("proposedAt", row.proposedAt)
                .executeUpdate()
                .flatMap {
                    session.createQuery(
                        "FROM CzCreditPolicyRevisionEntity WHERE contentHash = :hash",
                        CzCreditPolicyRevisionEntity::class.java,
                    ).setParameter("hash", row.contentHash).singleResult
                }
        }.map { it.toRevision() }

    override fun find(id: UUID): Uni<CzCreditPolicyRevision?> =
        sessions.withSession { it.find(CzCreditPolicyRevisionEntity::class.java, id) }.map { it?.toRevision() }

    override fun pending(): Uni<List<CzCreditPolicyRevision>> = sessions.withSession { session ->
        session.createQuery(
            "FROM CzCreditPolicyRevisionEntity WHERE state = :state ORDER BY proposedAt, id",
            CzCreditPolicyRevisionEntity::class.java,
        ).setParameter("state", ProposalState.PROPOSED).resultList
    }.map { rows -> rows.map { it.toRevision() } }

    override fun active(asOf: LocalDate): Uni<CzCreditPolicyRevision?> = sessions.withSession { session ->
        session.createQuery(
            "FROM CzCreditPolicyRevisionEntity WHERE state = :state AND effectiveFrom <= :day ORDER BY effectiveFrom DESC",
            CzCreditPolicyRevisionEntity::class.java,
        ).setParameter("state", ProposalState.EXECUTED)
            .setParameter("day", asOf)
            .setMaxResults(1)
            .singleResultOrNull
    }.map { it?.toRevision() }

    override fun decide(id: UUID, decision: Proposal<PolicyBundle>): Uni<Int> = sessions.withTransaction { session ->
        session.createMutationQuery(
            """
            UPDATE CzCreditPolicyRevisionEntity
            SET state = :state, decidedBy = :checker, decidedAt = :at, decisionReason = :reason
            WHERE id = :id AND state = :proposed
            """.trimIndent(),
        ).setParameter("state", decision.state)
            .setParameter("checker", decision.decidedBy)
            .setParameter("at", decision.decidedAt?.atOffset(ZoneOffset.UTC))
            .setParameter("reason", decision.decisionReason)
            .setParameter("id", id)
            .setParameter("proposed", ProposalState.PROPOSED)
            .executeUpdate()
    }
}

private fun CzCreditPolicyRevisionEntity.toRevision() = CzCreditPolicyRevision(
    id = id,
    revision = revision,
    effectiveFrom = effectiveFrom,
    payload = payload,
    contentHash = contentHash,
    state = state,
    proposedBy = proposedBy,
    proposedAt = proposedAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    decisionReason = decisionReason,
)
