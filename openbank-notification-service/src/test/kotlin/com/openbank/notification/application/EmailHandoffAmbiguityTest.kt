// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.notification.application.port.out.EmailMetricsPort
import com.openbank.notification.domain.model.EmailSendOutcome
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.mailer.reactive.ReactiveMailer
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class EmailHandoffAmbiguityTest {
    @Test
    fun `mailer timeout keeps PENDING and fails the message for reconciliation`() {
        val mailer = mockk<ReactiveMailer>()
        val metrics = mockk<EmailMetricsPort>(relaxed = true)
        val failure = IllegalStateException("SMTP response lost after handoff")
        every { mailer.send(any()) } returns Uni.createFrom().failure(failure)
        val consumer = NotificationConsumer(mailerMocked = false, pushFallbackEnabled = false).apply {
            this.mailer = mailer
            emailMetrics = metrics
        }
        val entity = NotificationEntity().apply { status = "PENDING" }
        val request = NotificationRequest(
            partyId = UUID.randomUUID(),
            channel = NotificationChannel.EMAIL,
            template = NotificationTemplate.TRANSACTION_COMPLETED,
            recipient = "synthetic@example.test",
            variables = emptyMap(),
            deduplicationKey = UUID.randomUUID(),
        )

        assertThatThrownBy {
            consumer.deliverEmail(request, request.recipient, "Synthetic update", "Body", entity)
                .await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("SMTP response lost")

        assertThat(entity.status).isEqualTo("PENDING")
        assertThat(entity.failureReason).isNull()
        verify(exactly = 1) {
            metrics.recordSend(NotificationTemplate.TRANSACTION_COMPLETED, EmailSendOutcome.IN_DOUBT)
        }
    }
}
