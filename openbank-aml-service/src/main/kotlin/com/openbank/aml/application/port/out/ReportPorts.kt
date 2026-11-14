// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.report.DispositionProposal
import com.openbank.aml.domain.report.ReportState
import com.openbank.aml.domain.report.SuspiciousTransactionReport
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

interface DispositionProposalRepository {
    /** `false` when the case already has a proposal awaiting a decision. */
    suspend fun insert(proposal: DispositionProposal): Boolean

    suspend fun find(proposalId: UUID): DispositionProposal?

    suspend fun pending(limit: Int): List<DispositionProposal>

    /** Compare-and-set on the state: `false` when someone else moved the proposal first. */
    suspend fun transition(proposal: DispositionProposal, from: ProposalState): Boolean
}

interface SuspiciousTransactionReportRepository {
    /** Report, links and outbox event in ONE transaction. */
    suspend fun insert(report: SuspiciousTransactionReport, event: OutboxMessage)

    suspend fun find(reportId: UUID): SuspiciousTransactionReport?

    suspend fun list(state: ReportState?, limit: Int): List<SuspiciousTransactionReport>

    /** Compare-and-set on the state, with the outbox event, in ONE transaction. */
    suspend fun update(report: SuspiciousTransactionReport, from: ReportState, event: OutboxMessage): Boolean

    /** How many of [caseIds] are monitoring cases of [partyId]. */
    suspend fun countMonitoringCases(partyId: UUID, caseIds: List<UUID>): Long

    /** How many of the report's movements are booked movements counted for [partyId]. */
    suspend fun countPartyMovements(partyId: UUID, report: SuspiciousTransactionReport): Long

    /** Approval instant of the oldest report that is approved and not filed. */
    suspend fun oldestApprovedNotFiled(): Instant?
}
