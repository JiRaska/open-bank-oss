// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@Suppress("DEPRECATION")
class AbstractOutboxEntityTest {
    @Test
    fun `toEntry preserves the repository pattern outbox state`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000012")
        val createdAt = Instant.parse("2026-09-29T08:00:00Z")
        val updatedAt = Instant.parse("2026-09-29T08:01:00Z")
        val sentAt = Instant.parse("2026-09-29T08:02:00Z")
        val entity = object : AbstractOutboxEntity() {}.apply {
            this.eventId = eventId
            this.aggregateId = aggregateId
            eventType = "PaymentSettled"
            payload = """{"paymentId":"p-1"}"""
            status = "FAILED"
            attemptCount = 3
            this.createdAt = createdAt
            this.updatedAt = updatedAt
            this.sentAt = sentAt
            lastError = "broker unavailable"
        }

        assertThat(entity.toEntry()).isEqualTo(
            OutboxEntry(
                eventId = eventId,
                aggregateId = aggregateId,
                eventType = "PaymentSettled",
                payload = """{"paymentId":"p-1"}""",
                status = OutboxStatus.FAILED,
                attemptCount = 3,
                createdAt = createdAt,
                updatedAt = updatedAt,
                sentAt = sentAt,
                lastError = "broker unavailable",
            ),
        )
    }

    @Test
    fun `toEntry rejects an unknown persisted status`() {
        val entity = object : AbstractOutboxEntity() {}.apply {
            eventId = UUID.fromString("00000000-0000-0000-0000-000000000011")
            aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000012")
            eventType = "PaymentSettled"
            payload = "{}"
            status = "UNKNOWN"
            createdAt = Instant.parse("2026-09-29T08:00:00Z")
            updatedAt = Instant.parse("2026-09-29T08:01:00Z")
        }

        assertThatThrownBy { entity.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
