// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Lifecycle of one supervisory return revision: DRAFT -> PACKAGED -> SUBMITTED -> ACKNOWLEDGED | REJECTED.
 *
 * A transport outcome other than [TransportOutcome.SUBMITTED] never moves the return to SUBMITTED:
 * a dry run or a failed transmission leaves it PACKAGED, so "nothing was sent" can never read as sent.
 */
enum class SubmissionState { DRAFT, PACKAGED, SUBMITTED, ACKNOWLEDGED, REJECTED }

/**
 * What a [com.openbank.finrep.application.port.out.SupervisoryReturnTransport] did with a package.
 * [DRY_RUN] is its own value on purpose — it is never folded into success (CLAUDE.md, the
 * `PushResult.skipped()` lesson): the default adapter transmits nothing and must say so.
 */
enum class TransportOutcome { SUBMITTED, DRY_RUN, REJECTED, FAILED }

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
) {
    fun packaged(frameworkVersion: String, taxonomy: String, hash: String, by: String, at: Instant): SupervisorySubmission {
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

    /** Applies a transport outcome. Only SUBMITTED and REJECTED change state. */
    fun afterTransport(outcome: TransportOutcome, reference: String?, at: Instant): SupervisorySubmission {
        requireState(SubmissionState.PACKAGED, "submit")
        return when (outcome) {
            TransportOutcome.SUBMITTED -> copy(state = SubmissionState.SUBMITTED, submittedAt = at, transportReference = reference)
            TransportOutcome.REJECTED -> copy(state = SubmissionState.REJECTED, transportReference = reference)
            TransportOutcome.DRY_RUN, TransportOutcome.FAILED -> this
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
