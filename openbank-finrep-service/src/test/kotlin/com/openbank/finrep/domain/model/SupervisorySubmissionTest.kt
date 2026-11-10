// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SupervisorySubmissionTest {
    private val at = Instant.parse("2026-07-01T08:00:00Z")
    private val draft = SupervisorySubmission(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        key = SubmissionKey("F01.01", LocalDate.parse("2026-06-30"), 1),
        state = SubmissionState.DRAFT,
        createdBy = "op",
        createdAt = at,
    )
    private val packaged = draft.packaged("4.2", "4.2.0.0", "a".repeat(64), "op", at)
    private val claimed = packaged.claimed(at)

    @Test
    fun `dry run and failure leave the return PACKAGED - nothing sent never reads as sent`() {
        assertThat(
            claimed.afterTransport(TransportOutcome.DRY_RUN, null, at).state,
        ).isEqualTo(SubmissionState.PACKAGED)
        assertThat(claimed.afterTransport(TransportOutcome.FAILED, null, at).state).isEqualTo(SubmissionState.PACKAGED)
        assertThat(claimed.afterTransport(TransportOutcome.DRY_RUN, null, at).submittedAt).isNull()
    }

    @Test
    fun `only a SUBMITTED outcome reaches SUBMITTED, and acknowledgement then settles it`() {
        val submitted = claimed.afterTransport(TransportOutcome.SUBMITTED, "R-1", at)
        assertThat(submitted.state).isEqualTo(SubmissionState.SUBMITTED)
        assertThat(submitted.submittedAt).isEqualTo(at)
        val ack = SubmissionAcknowledgement(true, "ACK-1", null, "op", at)
        assertThat(submitted.acknowledged(ack).state).isEqualTo(SubmissionState.ACKNOWLEDGED)
        assertThat(submitted.acknowledged(ack.copy(accepted = false)).state).isEqualTo(SubmissionState.REJECTED)
        assertThat(
            claimed.afterTransport(TransportOutcome.REJECTED, "R-2", at).state,
        ).isEqualTo(SubmissionState.REJECTED)
    }

    @Test
    fun `the transmission claim is exclusive and a transport outcome needs it`() {
        assertThat(claimed.state).isEqualTo(SubmissionState.SUBMITTING)
        assertThatThrownBy { claimed.claimed(at) }.isInstanceOf(SubmissionStateException::class.java)
        assertThatThrownBy { packaged.afterTransport(TransportOutcome.SUBMITTED, null, at) }
            .isInstanceOf(SubmissionStateException::class.java)
    }

    @Test
    fun `an unknown outcome is never resubmittable and only reconcile resolves it`() {
        val unknown = claimed.afterTransport(TransportOutcome.UNKNOWN, null, at)
        assertThat(unknown.state).isEqualTo(SubmissionState.OUTCOME_UNKNOWN)
        assertThat(unknown.claimedAt).isNull()
        assertThatThrownBy { unknown.claimed(at) }.isInstanceOf(SubmissionStateException::class.java)
        assertThat(unknown.reconciled(sent = true, reference = "R", at = at).state).isEqualTo(SubmissionState.SUBMITTED)
        assertThat(
            unknown.reconciled(sent = false, reference = null, at = at).state,
        ).isEqualTo(SubmissionState.PACKAGED)
    }

    @Test
    fun `a claim lapses only after its lease`() {
        val lease = java.time.Duration.ofMinutes(15)
        assertThat(claimed.claimExpired(at.plusSeconds(60), lease)).isFalse()
        assertThat(claimed.claimExpired(at.plus(lease).plusSeconds(1), lease)).isTrue()
        assertThat(claimed.claimLapsed().state).isEqualTo(SubmissionState.OUTCOME_UNKNOWN)
        assertThat(packaged.claimExpired(at.plus(lease).plusSeconds(1), lease)).isFalse()
    }

    @Test
    fun `illegal transitions are refused`() {
        assertThatThrownBy { draft.afterTransport(TransportOutcome.SUBMITTED, null, at) }
            .isInstanceOf(SubmissionStateException::class.java)
        assertThatThrownBy { packaged.acknowledged(SubmissionAcknowledgement(true, "A", null, "op", at)) }
            .isInstanceOf(SubmissionStateException::class.java)
        assertThatThrownBy { packaged.packaged("4.2", "t", "h", "op", at) }
            .isInstanceOf(SubmissionStateException::class.java)
    }

    @Test
    fun `key rejects blank template and non-positive revision`() {
        assertThatThrownBy { SubmissionKey(" ", LocalDate.EPOCH, 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SubmissionKey("F01.01", LocalDate.EPOCH, 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
