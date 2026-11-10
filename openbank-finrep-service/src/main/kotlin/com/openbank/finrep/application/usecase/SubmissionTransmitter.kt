// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.out.SupervisoryReturnPackage
import com.openbank.finrep.application.port.out.SupervisoryReturnTransport
import com.openbank.finrep.application.port.out.SupervisorySubmissionRepository
import com.openbank.finrep.application.port.out.TransportResult
import com.openbank.finrep.domain.model.SubmissionAttempt
import com.openbank.finrep.domain.model.SubmissionState
import com.openbank.finrep.domain.model.SupervisorySubmission
import com.openbank.finrep.domain.model.TransportOutcome
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The claim protocol around the supervisory transport: transmit under a held claim, record every
 * outcome fenced on the claim token, and lapse a claim whose holder is presumed dead.
 */
@ApplicationScoped
class SubmissionTransmitter(
    private val repository: SupervisorySubmissionRepository,
    private val transport: SupervisoryReturnTransport,
    private val clock: Clock,
    // How long a SUBMITTING claim may be held before its holder is presumed dead. Must exceed the
    // transport's own timeout, or a slow but live transmission would be marked OUTCOME_UNKNOWN.
    @ConfigProperty(name = "openbank.finrep.submission.claim-lease", defaultValue = "PT15M")
    private val lease: Duration,
) {
    fun claimExpired(submission: SupervisorySubmission): Boolean = submission.claimExpired(clock.instant(), lease)

    /**
     * Holds the claim from here on: every path out of this function records an outcome, and the
     * recording runs NonCancellable so a client disconnect cannot leave the row SUBMITTING.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun transmitClaimed(claimed: SupervisorySubmission, actor: String): SupervisorySubmission {
        val result = try {
            transport.transmit(claimed.toPackage())
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                record(claimed, TransportResult(TransportOutcome.UNKNOWN, detail = "cancelled mid-transmission"), actor)
            }
            throw e
        } catch (e: Exception) {
            // The adapter threw: it may have delivered before failing. Never assume FAILED.
            TransportResult(TransportOutcome.UNKNOWN, detail = "transport threw ${e.javaClass.simpleName}")
        }
        return withContext(NonCancellable) { record(claimed, result, actor) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun record(
        claimed: SupervisorySubmission,
        result: TransportResult,
        actor: String,
    ): SupervisorySubmission {
        val now = clock.instant()
        val mine = requireNotNull(claimed.claimId) { "record() needs a claim" }
        val next = claimed.afterTransport(result.outcome, result.reference, now)
        val attempt = attempt(claimed, transport.name, result.outcome, result.reference, result.detail, actor, now)
            .copy(claimId = mine)
        val moved = try {
            repository.transition(SubmissionState.SUBMITTING, next, attempt, mine)
        } catch (e: Exception) {
            // One retry; if the store is down the claim stays SUBMITTING and lapses to
            // OUTCOME_UNKNOWN after the lease — never back to a resubmittable PACKAGED.
            log.warnf(e, "recording outcome %s for submission %s failed; retrying once", result.outcome, claimed.id)
            repository.transition(SubmissionState.SUBMITTING, next, attempt, mine)
        }
        // OUR claim lapsed while we transmitted and nobody has resolved it yet: a late but real
        // answer still beats "unknown". Fenced on our token, so it can never touch a newer claim.
        if (moved ||
            repository.transition(SubmissionState.OUTCOME_UNKNOWN, next, attempt, mine)
        ) {
            return reload(claimed.id)
        }

        // A newer claimant or an operator owns the row now. Do NOT apply our result; record it,
        // as UNKNOWN under our own claim id, so an operator can see a possible second delivery.
        log.warnf(
            "late transport result %s (ref %s) for submission %s from lapsed claim %s; not applied",
            result.outcome,
            result.reference,
            claimed.id,
            mine,
        )
        repository.appendAttempt(
            attempt.copy(
                outcome = TransportOutcome.UNKNOWN,
                detail = "late result ${result.outcome} (ref ${result.reference}) from lapsed claim; not applied",
            ),
        )
        return reload(claimed.id)
    }

    suspend fun lapse(current: SupervisorySubmission, actor: String): SupervisorySubmission {
        val now = clock.instant()
        val attempt = attempt(
            current,
            "claim-lease",
            TransportOutcome.UNKNOWN,
            null,
            "claim lease expired; delivery unknown — reconcile with the supervisor",
            actor,
            now,
        )
        val lapsedClaim = requireNotNull(current.claimId) { "a SUBMITTING row always carries its claim id" }
        repository.transition(
            SubmissionState.SUBMITTING,
            current.claimLapsed(),
            attempt.copy(claimId = lapsedClaim),
            lapsedClaim,
            claimedBefore = now.minus(lease),
        )
        return reload(current.id)
    }

    suspend fun attempt(
        submission: SupervisorySubmission,
        transportName: String,
        outcome: TransportOutcome,
        reference: String?,
        detail: String?,
        actor: String,
        at: Instant,
    ) = SubmissionAttempt(
        id = Ids.newId(),
        submissionId = submission.id,
        attemptNo = repository.attempts(submission.id).size + 1,
        transport = transportName,
        outcome = outcome,
        transportReference = reference,
        detail = detail,
        attemptedBy = actor,
        attemptedAt = at,
    )

    private fun SupervisorySubmission.toPackage() = SupervisoryReturnPackage(
        submissionId = id,
        key = key,
        reportingFrameworkVersion = requireNotNull(reportingFrameworkVersion),
        taxonomyVersion = requireNotNull(taxonomyVersion),
        packageHash = requireNotNull(packageHash),
    )

    private suspend fun reload(id: UUID): SupervisorySubmission = requireNotNull(repository.findById(id))

    private companion object {
        val log: Logger = Logger.getLogger(SubmissionTransmitter::class.java)
    }
}
