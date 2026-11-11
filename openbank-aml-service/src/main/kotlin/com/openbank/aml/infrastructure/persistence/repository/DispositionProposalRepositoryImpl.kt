// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.persistence.repository

import com.openbank.aml.application.port.out.DispositionProposalRepository
import com.openbank.aml.domain.report.CaseDisposition
import com.openbank.aml.domain.report.DispositionProposal
import com.openbank.aml.domain.report.DispositionRequest
import com.openbank.aml.domain.report.OutcomeLabel
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.instant
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.mutate
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.requiredInstant
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.select
import com.openbank.aml.infrastructure.persistence.repository.NativeSql.utc
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DispositionProposalRepositoryImpl : DispositionProposalRepository {

    // ON CONFLICT on the partial unique index: a second open proposal for the case inserts nothing.
    override suspend fun insert(proposal: DispositionProposal): Boolean = Panache.withTransaction {
        mutate(
            """
            INSERT INTO aml_case_disposition_proposal
              (proposal_id, case_id, disposition, outcome_vocabulary, outcome_revision, outcome_hash, outcome_code,
               note, proposed_by, proposed_at, state)
            VALUES (:id, :caseId, :disposition, :vocabulary, :revision, :hash, :code,
               nullif(:note, ''), :proposedBy, :proposedAt, 'PROPOSED')
            ON CONFLICT DO NOTHING
            """,
            mapOf(
                "id" to UUID.fromString(proposal.id),
                "caseId" to proposal.action.caseId,
                "disposition" to proposal.action.disposition.name,
                "vocabulary" to proposal.action.outcome.vocabularyId,
                "revision" to proposal.action.outcome.vocabularyRevision,
                "hash" to proposal.action.outcome.vocabularyHash,
                "code" to proposal.action.outcome.code,
                "note" to (proposal.action.note ?: ""),
                "proposedBy" to proposal.proposedBy,
                "proposedAt" to utc(proposal.proposedAt),
            ),
        )
    }.awaitSuspending() == 1

    override suspend fun find(proposalId: UUID): DispositionProposal? = Panache.withSession {
        select("$SELECT WHERE proposal_id = :id", mapOf("id" to proposalId))
    }.awaitSuspending().firstOrNull()?.let(::toProposal)

    override suspend fun pending(limit: Int): List<DispositionProposal> = Panache.withSession {
        select("$SELECT WHERE state = 'PROPOSED' ORDER BY proposed_at LIMIT :limit", mapOf("limit" to limit))
    }.awaitSuspending().map(::toProposal)

    override suspend fun transition(proposal: DispositionProposal, from: ProposalState): Boolean = Panache.withTransaction {
        mutate(
            """
            UPDATE aml_case_disposition_proposal
               SET state = :state, decided_by = nullif(:decidedBy, ''), decided_at = cast(nullif(:decidedAt, '') as timestamptz),
                   decision_reason = nullif(:reason, ''), executed_at = cast(nullif(:executedAt, '') as timestamptz)
             WHERE proposal_id = :id AND state = :from
            """,
            mapOf(
                "state" to proposal.state.name,
                "decidedBy" to (proposal.decidedBy ?: ""),
                "decidedAt" to (proposal.decidedAt?.toString() ?: ""),
                "reason" to (proposal.decisionReason ?: ""),
                "executedAt" to (proposal.executedAt?.toString() ?: ""),
                "id" to UUID.fromString(proposal.id),
                "from" to from.name,
            ),
        )
    }.awaitSuspending() == 1

    private fun toProposal(row: Array<Any?>): DispositionProposal = Proposal(
        id = (row[ID] as UUID).toString(),
        action = DispositionRequest(
            caseId = row[CASE] as UUID,
            disposition = CaseDisposition.valueOf(row[DISPOSITION] as String),
            outcome = OutcomeLabel(row[VOCABULARY] as String, (row[REVISION] as Number).toInt(), row[HASH] as String, row[CODE] as String),
            note = row[NOTE] as? String,
        ),
        proposedBy = row[PROPOSED_BY] as String,
        proposedAt = requiredInstant(row[PROPOSED_AT]),
        state = ProposalState.valueOf(row[STATE] as String),
        decidedBy = row[DECIDED_BY] as? String,
        decidedAt = instant(row[DECIDED_AT]),
        decisionReason = row[REASON] as? String,
        executedAt = instant(row[EXECUTED_AT]),
    )

    private companion object {
        const val SELECT = """
            SELECT proposal_id, case_id, disposition, outcome_vocabulary, outcome_revision, outcome_hash, outcome_code,
                   note, proposed_by, proposed_at, state, decided_by, decided_at, decision_reason, executed_at
              FROM aml_case_disposition_proposal"""
        const val ID = 0
        const val CASE = 1
        const val DISPOSITION = 2
        const val VOCABULARY = 3
        const val REVISION = 4
        const val HASH = 5
        const val CODE = 6
        const val NOTE = 7
        const val PROPOSED_BY = 8
        const val PROPOSED_AT = 9
        const val STATE = 10
        const val DECIDED_BY = 11
        const val DECIDED_AT = 12
        const val REASON = 13
        const val EXECUTED_AT = 14
    }
}
