// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.notification.domain.model.NotificationOutcome
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import com.openbank.notification.domain.model.PushResult
import com.openbank.notification.domain.model.PushSendOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0252 phase 0 — the PUSH fan-out status mapping.
 *
 * The defect this pins: `PushResult.skipped(...)` carries `success = true`, the fan-out asked
 * `success > 0`, and so a party whose every push adapter was disabled had the notification stored
 * as SENT with `sentAt` set and an outcome event announcing a delivery that never left the
 * process. A disabled channel and a working one produced identical evidence.
 *
 * The first test is the falsification: it asserts the OLD predicate against the same input and
 * shows it disagrees. Without it these are three assertions that would pass against the bug.
 */
class PushFanOutOutcomeTest {

    @Test
    fun `the old success-based predicate disagrees with the fixed one on a skipped-only fan-out`() {
        val results = listOf(PushResult.skipped("APNs disabled"), PushResult.skipped("FCM disabled"))

        // What the code used to compute — every skipped send counted as a delivery.
        val oldDelivered = results.count { it.success }
        assertThat(oldDelivered).isEqualTo(2)
        assertThat(if (oldDelivered > 0) NotificationOutcome.SENT else NotificationOutcome.FAILED)
            .isEqualTo(NotificationOutcome.SENT)

        // What it computes now.
        val accepted = results.count { it.outcome == PushSendOutcome.ACCEPTED }
        val skipped = results.count { it.outcome == PushSendOutcome.SKIPPED }
        assertThat(accepted).isZero()
        assertThat(NotificationConsumer.pushOutcomeOf(accepted, skipped))
            .isEqualTo(NotificationOutcome.SUPPRESSED)
        assertThat(NotificationConsumer.pushReasonOf(accepted, skipped))
            .isEqualTo(NotificationOutcomeEvent.REASON_PUSH_ADAPTER_DISABLED)
    }

    @Test
    fun `at least one acceptance is SENT with no reason, even alongside skips and failures`() {
        assertThat(NotificationConsumer.pushOutcomeOf(accepted = 1, skipped = 2))
            .isEqualTo(NotificationOutcome.SENT)
        assertThat(NotificationConsumer.pushReasonOf(accepted = 1, skipped = 2)).isNull()
    }

    @Test
    fun `only provider rejections are FAILED`() {
        assertThat(NotificationConsumer.pushOutcomeOf(accepted = 0, skipped = 0))
            .isEqualTo(NotificationOutcome.FAILED)
        assertThat(NotificationConsumer.pushReasonOf(accepted = 0, skipped = 0))
            .isEqualTo(NotificationOutcomeEvent.REASON_PUSH_REJECTED)
    }

    @Test
    fun `PushResult separates acceptance from a disabled adapter`() {
        assertThat(PushResult.ok("apns-id-1").outcome).isEqualTo(PushSendOutcome.ACCEPTED)
        assertThat(PushResult.skipped("APNs disabled").outcome).isEqualTo(PushSendOutcome.SKIPPED)
        assertThat(PushResult.failed("HTTP_410", "BadDeviceToken", invalidToken = true).outcome)
            .isEqualTo(PushSendOutcome.FAILED)

        // Both are `success` — which is exactly why reading that flag alone was not enough.
        assertThat(PushResult.ok("id").success).isTrue()
        assertThat(PushResult.skipped("off").success).isTrue()
    }
}
