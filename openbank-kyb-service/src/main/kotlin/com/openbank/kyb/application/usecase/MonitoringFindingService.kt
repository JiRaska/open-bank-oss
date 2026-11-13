// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.ConcurrentCaseUpdateException
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.domain.model.CaseTransitionException
import com.openbank.kyb.domain.model.MonitoringFinding
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What became of one finding. */
enum class MonitoringFindingOutcome(val tag: String) {
    /** The finding was recorded and waits for an operator. */
    REVIEW_OPENED("review_opened"),

    /** The finding id is already on the case: a redelivery, nothing written. */
    DUPLICATE("duplicate"),

    /** The subject is known but its case has ended or no longer carries it. */
    NOT_MONITORED("not_monitored"),

    /** No case of this service was ever registered under the reference. */
    UNKNOWN_SUBJECT("unknown_subject"),
}

/** A finding as it arrived, before it is known which subject of which case it is about. */
data class ReportedFinding(
    val findingId: UUID,
    val matchKind: String,
    val listType: String,
    val listEntryRef: String,
    val listEntryVersion: String,
    val matchScore: Double,
    val matchStatus: String,
    val detection: String,
)

/**
 * Records a `MONITORED_SUBJECT_MATCHED` finding (ADR-0376 D6) on the business case its subject
 * belongs to, for an operator to decide (ADR-0256 D2: the trigger decides nothing).
 *
 * Idempotent on the finding id, and atomically so: the finding is written with the case row,
 * under the case's optimistic lock. A redelivery finds the id and writes nothing; two deliveries
 * racing each other collide on the version, and the loser re-reads and finds the id.
 *
 * The case's status does not change and no event is published. kyb's lifecycle events reach
 * services that talk to the customer, and that a finding exists is exactly what must not be said.
 */
@ApplicationScoped
class MonitoringFindingService {

    @Inject lateinit var store: MonitoringRegistrationStore

    @Inject lateinit var cases: BusinessOnboardingCaseRepository

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(MonitoringFindingService::class.java)

    suspend fun handle(subjectRef: String, reported: ReportedFinding): MonitoringFindingOutcome {
        val row = store.findBySubjectRef(subjectRef) ?: return MonitoringFindingOutcome.UNKNOWN_SUBJECT
        // An event has nobody to retry for it, so a lost race is re-read and re-applied here.
        repeat(ATTEMPTS - 1) {
            try {
                return applyOnce(row.caseId, row.subjectKey, reported)
            } catch (e: ConcurrentCaseUpdateException) {
                log.infof("kyb case %s changed while a finding was recorded; re-reading (%s)", row.caseId, e.message)
            }
        }
        return applyOnce(row.caseId, row.subjectKey, reported)
    }

    private suspend fun applyOnce(caseId: UUID, subjectKey: String, r: ReportedFinding): MonitoringFindingOutcome {
        val case = cases.findById(caseId) ?: return MonitoringFindingOutcome.UNKNOWN_SUBJECT
        val finding = MonitoringFinding(
            findingId = r.findingId,
            subjectKey = subjectKey,
            matchKind = r.matchKind,
            listType = r.listType,
            listEntryRef = r.listEntryRef,
            listEntryVersion = r.listEntryVersion,
            matchScore = r.matchScore,
            matchStatus = r.matchStatus,
            detection = r.detection,
            receivedAt = Instant.now(clock),
        )
        val next = try {
            case.monitoringMatched(finding)
        } catch (e: CaseTransitionException) {
            log.infof("monitoring finding %s not recorded on kyb case %s: %s", r.findingId, caseId, e.message)
            return MonitoringFindingOutcome.NOT_MONITORED
        }
        if (next === case) return MonitoringFindingOutcome.DUPLICATE
        cases.update(next, null)
        log.infof("kyb case %s has a new monitoring finding %s (%s)", caseId, r.findingId, r.detection)
        return MonitoringFindingOutcome.REVIEW_OPENED
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
