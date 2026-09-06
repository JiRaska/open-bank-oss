// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.webhook

import com.openbank.notification.application.OversightSignal
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * The Slack half of the ADR-0059 D4 off-by-default posture (its Teams sibling already has one).
 *
 * Every case here asserts the *no-egress* branch: a URL is configured but the flag is off, or the
 * flag is on with nothing to send to. The test can only pass because `publish` short-circuits —
 * an unreachable host would otherwise be attempted and the assertion would wait on real I/O.
 */
class SlackOversightWebhookPublisherTest {

    private fun signal() = OversightSignal(
        template = NotificationTemplate.KYC_REJECTED,
        primaryChannel = NotificationChannel.EMAIL,
        status = NotificationStatus.PENDING,
        occurredAt = Instant.parse("2026-09-05T09:00:00Z"),
    )

    @Test
    fun `publish - disabled with a configured URL - returns false without any HTTP call`() {
        val pub = SlackOversightWebhookPublisher().also {
            it.enabled = false
            // A syntactically valid but unroutable host: reaching it would fail, not return false.
            it.url = Optional.of("https://hooks.slack.test.invalid/services/T000/B000/xxx")
        }

        assertThat(pub.publish(signal()).await().indefinitely()).isFalse()
    }

    @Test
    fun `publish - enabled but URL absent - returns false without any HTTP call`() {
        val pub = SlackOversightWebhookPublisher().also {
            it.enabled = true
            it.url = Optional.empty()
        }

        assertThat(pub.publish(signal()).await().indefinitely()).isFalse()
    }

    @Test
    fun `publish - enabled but URL blank - returns false, the empty-env-var shape`() {
        // `${SLACK_WEBHOOK_URL:}` expands to whitespace/empty rather than absent — a blank must be
        // treated as unconfigured, not handed to URI.create where it would throw.
        val pub = SlackOversightWebhookPublisher().also {
            it.enabled = true
            it.url = Optional.of("   ")
        }

        assertThat(pub.publish(signal()).await().indefinitely()).isFalse()
    }
}
