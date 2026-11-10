// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Lifecycle of one supervisory return revision:
 * DRAFT -> PACKAGED -> SUBMITTING -> SUBMITTED -> ACKNOWLEDGED | REJECTED.
 *
 * A transport outcome other than [TransportOutcome.SUBMITTED] never moves the return to SUBMITTED:
 * a dry run or a failed transmission releases it to PACKAGED, so "nothing was sent" never reads as sent.
 *
 * OUTCOME_UNKNOWN means the transport MAY have delivered (it threw, the request was cancelled
 * mid-call, or the claim's lease expired). It is never resubmitted automatically: only an
 * operator reconcile, after checking with the supervisor, moves it to SUBMITTED or back to PACKAGED.
 *
 * SUBMITTING is the claim held while the transport is called: only one caller can move a return
 * PACKAGED -> SUBMITTING (a conditional UPDATE), so two concurrent submits never both transmit.
 */
enum class SubmissionState { DRAFT, PACKAGED, SUBMITTING, OUTCOME_UNKNOWN, SUBMITTED, ACKNOWLEDGED, REJECTED }

/**
 * What a [com.openbank.finrep.application.port.out.SupervisoryReturnTransport] did with a package.
 * [DRY_RUN] is its own value on purpose — it is never folded into success (CLAUDE.md, the
 * `PushResult.skipped()` lesson): the default adapter transmits nothing and must say so.
 *
 * An adapter returns [FAILED] only when it is CERTAIN nothing reached the supervisor. [UNKNOWN] is
 * recorded by the service, never returned as success, whenever the call may have delivered.
 */
enum class TransportOutcome { SUBMITTED, DRY_RUN, REJECTED, FAILED, UNKNOWN }

/** Identity of a return revision; the idempotency key of the whole lifecycle. */
data class SubmissionKey(val templateId: String, val referencePeriod: LocalDate, val revision: Int) {
    init {
        require(templateId.isNotBlank()) { "templateId must not be blank" }
        require(revision >= 1) { "revision must be >= 1" }
    }
}

/** One call to the transport, recorded whatever its outcome. */
data class SubmissionAttempt(
    val id: UUID,
    val submissionId: UUID,
    val attemptNo: Int,
    val transport: String,
    val outcome: TransportOutcome,
    val transportReference: String?,
    val detail: String?,
    val attemptedBy: String,
    val attemptedAt: Instant,
)

/** The regulator's answer to a SUBMITTED return, recorded by an operator. */
data class SubmissionAcknowledgement(
    val accepted: Boolean,
    val reference: String,
    val message: String?,
    val recordedBy: String,
    val recordedAt: Instant,
)

class SubmissionStateException(message: String) : IllegalStateException(message)

data class SupervisorySubmission(
    val id: UUID,
    val key: SubmissionKey,
    val state: SubmissionState,
    val createdBy: String,
    val createdAt: Instant,
    val reportingFrameworkVersion: String? = null,
    val taxonomyVersion: String? = null,
    val packageHash: String? = null,
    val packagedBy: String? = null,
    val packagedAt: Instant? = null,
    val submittedAt: Instant? = null,
    val transportReference: String? = null,
    val acknowledgement: SubmissionAcknowledgement? = null,
    val claimedAt: Instant? = null,
) {
    fun packaged(
        frameworkVersion: String,
        taxonomy: String,
        hash: String,
        by: String,
        at: Instant,
    ): SupervisorySubmission {
        requireState(SubmissionState.DRAFT, "package")
        return copy(
            state = SubmissionState.PACKAGED,
            reportingFrameworkVersion = frameworkVersion,
            taxonomyVersion = taxonomy,
            packageHash = hash,
            packagedBy = by,
            packagedAt = at,
        )
    }

    /** The in-memory image of the claim the repository takes with a conditional UPDATE. */
    fun claimed(at: Instant): SupervisorySubmission {
        requireState(SubmissionState.PACKAGED, "submit")
        return copy(state = SubmissionState.SUBMITTING, claimedAt = at)
    }

    /** True when a SUBMITTING claim is older than [lease]: its holder is presumed dead. */
    fun claimExpired(now: Instant, lease: java.time.Duration): Boolean =
        state == SubmissionState.SUBMITTING && claimedAt?.plus(lease)?.isBefore(now) ?: true

    /** A claim whose holder died: the transport may have been called, so the outcome is unknown. */
    fun claimLapsed(): SupervisorySubmission {
        requireState(SubmissionState.SUBMITTING, "expire the claim of")
        return copy(state = SubmissionState.OUTCOME_UNKNOWN, claimedAt = null)
    }

    /** Operator resolution of an unknown outcome, after checking with the supervisor. */
    fun reconciled(sent: Boolean, reference: String?, at: Instant): SupervisorySubmission {
        requireState(SubmissionState.OUTCOME_UNKNOWN, "reconcile")
        return if (sent) {
            copy(state = SubmissionState.SUBMITTED, submittedAt = at, transportReference = reference)
        } else {
            copy(state = SubmissionState.PACKAGED)
        }
    }

    /** Applies a transport outcome to a claimed return. DRY_RUN and FAILED release it to PACKAGED. */
    fun afterTransport(outcome: TransportOutcome, reference: String?, at: Instant): SupervisorySubmission {
        requireState(SubmissionState.SUBMITTING, "record a transport outcome for")
        val released = copy(claimedAt = null)
        return when (outcome) {
            TransportOutcome.SUBMITTED -> released.copy(
                state = SubmissionState.SUBMITTED,
                submittedAt = at,
                transportReference = reference,
            )

            TransportOutcome.REJECTED -> released.copy(state = SubmissionState.REJECTED, transportReference = reference)

            TransportOutcome.DRY_RUN, TransportOutcome.FAILED -> released.copy(state = SubmissionState.PACKAGED)

            TransportOutcome.UNKNOWN -> released.copy(state = SubmissionState.OUTCOME_UNKNOWN)
        }
    }

    fun acknowledged(ack: SubmissionAcknowledgement): SupervisorySubmission {
        requireState(SubmissionState.SUBMITTED, "record an acknowledgement for")
        return copy(
            state = if (ack.accepted) SubmissionState.ACKNOWLEDGED else SubmissionState.REJECTED,
            acknowledgement = ack,
        )
    }

    private fun requireState(expected: SubmissionState, action: String) {
        if (state != expected) {
            throw SubmissionStateException("cannot $action a submission in state $state (expected $expected)")
        }
    }
}
