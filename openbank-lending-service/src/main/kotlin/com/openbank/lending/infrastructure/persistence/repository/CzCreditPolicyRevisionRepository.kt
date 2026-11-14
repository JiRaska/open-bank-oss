// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.CzCreditPolicyCommand
import com.openbank.lending.application.port.out.CzCreditPolicyRevision
import com.openbank.lending.application.port.out.CzCreditPolicyStore
import com.openbank.lending.application.port.out.CzCreditPolicyWrite
import com.openbank.lending.infrastructure.persistence.entity.CzCreditPolicyCommandReceiptEntity
import com.openbank.lending.infrastructure.persistence.entity.CzCreditPolicyRevisionEntity
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class CzCreditPolicyRevisionRepository(private val sessions: Mutiny.SessionFactory) : CzCreditPolicyStore {
    override fun propose(
        command: CzCreditPolicyCommand,
        id: UUID,
        create: () -> CzCreditPolicyRevision,
    ): Uni<CzCreditPolicyWrite> = write(command, id, ProposalState.PROPOSED) { session ->
        insertProposal(session, create())
    }

    private fun insertProposal(session: Mutiny.Session, row: CzCreditPolicyRevision): Uni<CzCreditPolicyRevision> =
        session.createNativeMutationQuery(
            """
            INSERT INTO cz_credit_policy_revision
                (id, revision, effective_from, payload, content_hash, state, proposed_by, proposed_at)
            VALUES (:id, :revision, :effectiveFrom, :payload, :hash, 'PROPOSED', :proposer, :proposedAt)
            ON CONFLICT (content_hash) WHERE state IN ('PROPOSED', 'EXECUTED') DO NOTHING
            """.trimIndent(),
        ).setParameter("id", row.id)
            .setParameter("revision", row.revision)
            .setParameter("effectiveFrom", row.effectiveFrom)
            .setParameter("payload", row.payload)
            .setParameter("hash", row.contentHash)
            .setParameter("proposer", row.proposedBy)
            .setParameter("proposedAt", row.proposedAt)
            .executeUpdate()
            .flatMap { inserted ->
                if (inserted == 0) {
                    throw ResourceConflictException(
                        "An identical Czech credit policy is already pending or approved",
                        "CREDIT_POLICY_DUPLICATE_CONTENT",
                    )
                }
                session.find(CzCreditPolicyRevisionEntity::class.java, row.id).map { it.toRevision() }
            }

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

    override fun decide(
        command: CzCreditPolicyCommand,
        id: UUID,
        approve: Boolean,
        decide: (CzCreditPolicyRevision) -> Proposal<PolicyBundle>,
    ): Uni<CzCreditPolicyWrite> = write(
        command,
        id,
        if (approve) ProposalState.EXECUTED else ProposalState.REJECTED,
    ) { session ->
        session.find(CzCreditPolicyRevisionEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE).flatMap { entity ->
            val row = entity ?: throw ResourceNotFoundException("Unknown Czech credit policy proposal")
            val decision = decide(row.toRevision())
            row.state = decision.state
            row.decidedBy = decision.decidedBy
            // Match the persisted precision so a replay returns exactly the first decision timestamp.
            row.decidedAt = decision.decidedAt?.atOffset(ZoneOffset.UTC)?.truncatedTo(ChronoUnit.MICROS)
            row.decisionReason = decision.decisionReason
            session.flush().map { row.toRevision() }
        }
    }

    /** The deferred receipt FK permits reserving the command before inserting its target proposal. */
    private fun write(
        command: CzCreditPolicyCommand,
        id: UUID,
        responseState: ProposalState,
        mutate: (Mutiny.Session) -> Uni<CzCreditPolicyRevision>,
    ): Uni<CzCreditPolicyWrite> = sessions.withTransaction { session ->
        session.createNativeMutationQuery(
            """
            INSERT INTO cz_credit_policy_command_receipt (scoped_key_digest, request_hash, proposal_id, response_state)
            VALUES (:key, :requestHash, :proposalId, :responseState)
            ON CONFLICT (scoped_key_digest) DO NOTHING
            """.trimIndent(),
        ).setParameter("key", command.keyDigest)
            .setParameter("requestHash", command.requestHash)
            .setParameter("proposalId", id)
            .setParameter("responseState", responseState.name)
            .executeUpdate()
            .flatMap { inserted ->
                if (inserted == 1) {
                    mutate(session).map { CzCreditPolicyWrite(it, replayed = false) }
                } else {
                    replay(session, command)
                }
            }
    }

    private fun replay(session: Mutiny.Session, command: CzCreditPolicyCommand): Uni<CzCreditPolicyWrite> =
        session.find(CzCreditPolicyCommandReceiptEntity::class.java, command.keyDigest).flatMap { receipt ->
            if (receipt.requestHash != command.requestHash) throw IdempotencyKeyReusedException()
            session.find(CzCreditPolicyRevisionEntity::class.java, receipt.proposalId).map { entity ->
                val row = entity.toRevision()
                val original = if (receipt.responseState == ProposalState.PROPOSED) {
                    row.copy(state = ProposalState.PROPOSED, decidedBy = null, decidedAt = null, decisionReason = null)
                } else {
                    check(receipt.responseState == row.state) {
                        "The policy command receipt and terminal state disagree"
                    }
                    row
                }
                CzCreditPolicyWrite(original, replayed = true)
            }
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
