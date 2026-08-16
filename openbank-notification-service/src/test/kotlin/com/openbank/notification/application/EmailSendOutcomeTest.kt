// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.notification.domain.model.EmailSendOutcome
import com.openbank.notification.domain.model.NotificationOutcome
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #4737 — the EMAIL send status mapping.
 *
 * The defect this pins: a mocked `ReactiveMailer` completes with no failure, the code asked only
 * `failure != null`, and so a deployment carrying `QUARKUS_MAILER_MOCK=true` (which the sandbox
 * does, deliberately) stored the notification as SENT with `sent_at` populated and announced a
 * delivery that never left the process.
 *
 * The first test is the falsification: it asserts the OLD predicate against the same input and
 * shows it disagrees. Without it the rest would pass against the bug — the mocked case is exactly
 * the case a "did it throw?" test cannot distinguish, which is why the bug survived a green suite.
 *
 * Mirrors `PushFanOutOutcomeTest` on purpose: the same defect, the same three-state vocabulary,
 * and deliberately not a second shape.
 */
class EmailSendOutcomeTest {

    @Test
    fun `the old failure-only predicate disagrees with the fixed one on a mocked send`() {
        // The mocked send as the reactive chain observes it: the call completed, nothing threw.
        val failure: Throwable? = null
        val mailerMocked = true

        // What the code used to compute — no failure was read as a delivery.
        val oldOutcome = if (failure != null) NotificationOutcome.FAILED else NotificationOutcome.SENT
        assertThat(oldOutcome).isEqualTo(NotificationOutcome.SENT)

        // What it computes now, from the same two facts.
        val sendOutcome = when {
            failure != null -> EmailSendOutcome.FAILED
            mailerMocked -> EmailSendOutcome.MOCKED
            else -> EmailSendOutcome.ACCEPTED
        }
        assertThat(sendOutcome).isEqualTo(EmailSendOutcome.MOCKED)
        assertThat(NotificationConsumer.emailOutcomeOf(sendOutcome))
            .isEqualTo(NotificationOutcome.SUPPRESSED)
        assertThat(NotificationConsumer.emailOutcomeOf(sendOutcome))
            .isNotEqualTo(oldOutcome)
        assertThat(NotificationConsumer.emailReasonOf(sendOutcome))
            .isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
    }

    @Test
    fun `a mocked send is SUPPRESSED, never FAILED — nothing was rejected and nothing is retryable`() {
        assertThat(NotificationConsumer.emailOutcomeOf(EmailSendOutcome.MOCKED))
            .isEqualTo(NotificationOutcome.SUPPRESSED)
        // Folding a configuration state into the delivery-failure series would make that series
        // unusable for alerting — the mirror image of the bug being fixed.
        assertThat(NotificationConsumer.emailOutcomeOf(EmailSendOutcome.MOCKED))
            .isNotEqualTo(NotificationOutcome.FAILED)
    }

    @Test
    fun `a real acceptance is SENT with no reason`() {
        assertThat(NotificationConsumer.emailOutcomeOf(EmailSendOutcome.ACCEPTED))
            .isEqualTo(NotificationOutcome.SENT)
        assertThat(NotificationConsumer.emailReasonOf(EmailSendOutcome.ACCEPTED)).isNull()
    }

    @Test
    fun `a mailer rejection is FAILED and keeps its own reason`() {
        assertThat(NotificationConsumer.emailOutcomeOf(EmailSendOutcome.FAILED))
            .isEqualTo(NotificationOutcome.FAILED)
        assertThat(NotificationConsumer.emailReasonOf(EmailSendOutcome.FAILED))
            .isEqualTo(NotificationOutcomeEvent.REASON_MAILER_REFUSED)
    }

    @Test
    fun `SENT is reachable only from ACCEPTED, so no configuration state can assert a delivery`() {
        val sentFrom = EmailSendOutcome.entries.filter {
            NotificationConsumer.emailOutcomeOf(it) == NotificationOutcome.SENT
        }
        assertThat(sentFrom).containsExactly(EmailSendOutcome.ACCEPTED)
    }

    @Test
    fun `every outcome maps to a distinct status, so no two states share a number`() {
        val statuses = EmailSendOutcome.entries.map { NotificationConsumer.emailOutcomeOf(it) }
        assertThat(statuses).doesNotHaveDuplicates()
    }

    @Test
    fun `a reason accompanies exactly the non-delivering outcomes`() {
        val withReason = EmailSendOutcome.entries.filter { NotificationConsumer.emailReasonOf(it) != null }
        assertThat(withReason).containsExactlyInAnyOrder(EmailSendOutcome.MOCKED, EmailSendOutcome.FAILED)
        // The two must never collapse into one code: "switched off" and "rejected" are different
        // problems with different owners, the same distinction no_active_consent /
        // consent_check_unavailable already draws.
        assertThat(NotificationConsumer.emailReasonOf(EmailSendOutcome.MOCKED))
            .isNotEqualTo(NotificationConsumer.emailReasonOf(EmailSendOutcome.FAILED))
    }
}
