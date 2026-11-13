// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.ConcurrentCaseUpdateException
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.domain.model.CaseTransitionException
import com.openbank.kyb.domain.model.MonitoringMatch
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What became of one finding. Ordered: the first that applies to any case of the subject wins. */
enum class MonitoringFindingOutcome(val tag: String) {
    /** The finding was recorded and the case is under review. */
    REVIEW_OPENED("review_opened"),

    /** The finding id is already on the case: a redelivery, nothing written. */
    DUPLICATE("duplicate"),

    /** The subject is known but its case has ended or no longer carries it. */
    NOT_MONITORED("not_monitored"),

    /** No case of this service was ever registered under the reference. */
    UNKNOWN_SUBJECT("unknown_subject"),
}

/**
 * Turns a `MONITORED_SUBJECT_MATCHED` finding (ADR-0376 D6) into a review of the business case
 * the subject belongs to (ADR-0256 D2: the trigger opens a case for a human, it decides nothing).
 *
 * Idempotent on the finding id, and atomically so: the id is stored on the screened subject
 * inside the case row, so "finding recorded" and "case under review" are one write under the
 * case's optimistic lock. A redelivery finds the id and writes nothing; two deliveries racing
 * each other collide on the version, and the loser re-reads and finds the id.
 *
 * No event is published for the review. kyb's lifecycle events reach services that talk to the
 * customer, and "your approved company is under review again" is exactly what must not be said.
 * Operators learn of it from the review queue and from the alert on unresolved monitoring reviews.
 */
@ApplicationScoped
class MonitoringFindingService {

    @Inject lateinit var store: MonitoringRegistrationStore

    @Inject lateinit var cases: BusinessOnboardingCaseRepository

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(MonitoringFindingService::class.java)

    suspend fun handle(subjectRef: String, match: MonitoringMatch): MonitoringFindingOutcome {
        val rows = store.findBySubjectRef(subjectRef)
        if (rows.isEmpty()) return MonitoringFindingOutcome.UNKNOWN_SUBJECT
        // One reference can sit on several cases of the same entity; each of them is reviewed.
        return rows.map { applyTo(it.caseId, subjectRef, match) }.minBy { it.ordinal }
    }

    /** An event has nobody to retry for it, so a lost race is re-read and re-applied here. */
    private suspend fun applyTo(caseId: UUID, subjectRef: String, match: MonitoringMatch): MonitoringFindingOutcome {
        repeat(ATTEMPTS - 1) {
            try {
                return applyOnce(caseId, subjectRef, match)
            } catch (e: ConcurrentCaseUpdateException) {
                log.infof("kyb case %s changed while a monitoring finding was applied; re-reading (%s)", caseId, e.message)
            }
        }
        return applyOnce(caseId, subjectRef, match)
    }

    private suspend fun applyOnce(caseId: UUID, subjectRef: String, match: MonitoringMatch): MonitoringFindingOutcome {
        val case = cases.findById(caseId) ?: return MonitoringFindingOutcome.UNKNOWN_SUBJECT
        val next = try {
            case.monitoringMatched(subjectRef, match, Instant.now(clock))
        } catch (e: CaseTransitionException) {
            log.infof("monitoring finding %s not applied to kyb case %s: %s", match.findingId, caseId, e.message)
            return MonitoringFindingOutcome.NOT_MONITORED
        }
        if (next === case) return MonitoringFindingOutcome.DUPLICATE
        cases.update(next, null)
        log.infof("kyb case %s sent to review by monitoring finding %s (%s)", caseId, match.findingId, match.detection)
        return MonitoringFindingOutcome.REVIEW_OPENED
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
