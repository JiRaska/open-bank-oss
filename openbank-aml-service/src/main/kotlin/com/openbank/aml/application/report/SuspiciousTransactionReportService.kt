// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.SuspiciousTransactionReportRepository
import com.openbank.aml.domain.report.AmlReportStateChangedEvent
import com.openbank.aml.domain.report.ReportState
import com.openbank.aml.domain.report.ReportedMovement
import com.openbank.aml.domain.report.SuspiciousTransactionReport
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ReportNotFoundException(id: UUID) : ResourceNotFoundException("report not found: $id")

class ReportConflictException(message: String) : ResourceConflictException(message)

/**
 * Lifecycle of the suspicious-transaction report (ADR-0371 §6, §7, §10).
 *
 * Every actor is the authenticated caller. The event written with each transition carries the
 * report id and the new state and nothing else — no party, no case, no narrative, no reference —
 * because anything on `openbank.aml.events` is readable by other services.
 */
@ApplicationScoped
class SuspiciousTransactionReportService(
    private val reports: SuspiciousTransactionReportRepository,
    private val outcomes: DecisionOutcomes,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    suspend fun draft(
        partyId: UUID,
        narrative: String,
        caseIds: List<UUID>,
        movements: List<ReportedMovement>,
        drafter: String,
    ): SuspiciousTransactionReport {
        val report = SuspiciousTransactionReport.draft(UUID.randomUUID(), partyId, narrative, caseIds, movements, drafter, clock.instant())
        // A report may only rest on this party's own monitoring cases and counted movements.
        require(reports.countMonitoringCases(partyId, report.caseIds) == report.caseIds.size.toLong()) {
            "every linked case must be a transaction-monitoring case of the reported party"
        }
        require(reports.countPartyMovements(partyId, report) == report.movements.size.toLong()) {
            "every linked movement must be a booked movement of the reported party"
        }
        reports.insert(report, event(report))
        return report
    }

    suspend fun get(reportId: UUID): SuspiciousTransactionReport = reports.find(reportId) ?: throw ReportNotFoundException(reportId)

    suspend fun list(state: ReportState?, limit: Int): List<SuspiciousTransactionReport> =
        reports.list(state, limit.coerceIn(1, MAX_PAGE))

    suspend fun approve(reportId: UUID, approver: String): SuspiciousTransactionReport {
        val current = get(reportId)
        return save(current.approve(approver, clock.instant()), current.state)
    }

    suspend fun recordFiling(
        reportId: UUID,
        reference: String?,
        filedAt: Instant?,
        outcomeCode: String?,
        officer: String,
    ): SuspiciousTransactionReport {
        requireNotNull(reference) { "filingReference is required" }
        requireNotNull(filedAt) { "filedAt is required" }
        val current = get(reportId)
        val now = clock.instant()
        return save(current.recordFiling(reference, filedAt, officer, now, outcomes.label(outcomeCode, now)), current.state)
    }

    suspend fun withdraw(reportId: UUID, outcomeCode: String?, officer: String): SuspiciousTransactionReport {
        val current = get(reportId)
        val now = clock.instant()
        return save(current.withdraw(officer, now, outcomes.label(outcomeCode, now)), current.state)
    }

    private suspend fun save(next: SuspiciousTransactionReport, from: ReportState): SuspiciousTransactionReport {
        if (!reports.update(next, from, event(next))) {
            throw ReportConflictException("report ${next.id} was changed by someone else")
        }
        return next
    }

    private fun event(report: SuspiciousTransactionReport) = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = report.id,
        eventType = EVENT_STATE_CHANGED,
        payload = objectMapper.writeValueAsString(AmlReportStateChangedEvent(report.id, report.state, report.updatedAt)),
    )

    companion object {
        const val EVENT_STATE_CHANGED = "aml.report.state_changed.v1"
        const val MAX_PAGE = 200
    }
}
