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

class TeamsOversightWebhookPublisherTest {

    private fun signal() = OversightSignal(
        template = NotificationTemplate.ACCOUNT_FROZEN,
        primaryChannel = NotificationChannel.EMAIL,
        status = NotificationStatus.PENDING,
        occurredAt = Instant.parse("2026-06-29T05:05:00Z"),
    )

    @Test
    fun `publish - disabled - returns false without any HTTP call`() {
        val pub = TeamsOversightWebhookPublisher().also {
            it.enabled = false
            it.url = Optional.of("https://xxx.webhook.office.com/webhookb2/test")
        }

        val result = pub.publish(signal()).await().indefinitely()

        assertThat(result).isFalse()
    }

    @Test
    fun `publish - enabled but no URL - returns false without any HTTP call`() {
        val pub = TeamsOversightWebhookPublisher().also {
            it.enabled = true
            it.url = Optional.empty()
        }

        val result = pub.publish(signal()).await().indefinitely()

        assertThat(result).isFalse()
    }

    @Test
    fun `publish - enabled and blank URL - returns false without any HTTP call`() {
        val pub = TeamsOversightWebhookPublisher().also {
            it.enabled = true
            it.url = Optional.of("   ")
        }

        val result = pub.publish(signal()).await().indefinitely()

        assertThat(result).isFalse()
    }
}
