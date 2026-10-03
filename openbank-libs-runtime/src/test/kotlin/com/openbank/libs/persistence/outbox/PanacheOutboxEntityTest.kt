// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PanacheOutboxEntityTest {

    @Test
    fun `mapped properties retain their persisted values`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val createdAt = Instant.parse("2026-09-29T09:00:00Z")
        val updatedAt = Instant.parse("2026-09-29T09:01:00Z")
        val sentAt = Instant.parse("2026-09-29T09:02:00Z")
        val entity = PanacheOutboxEntity().apply {
            this.eventId = eventId
            this.aggregateId = aggregateId
            eventType = "PaymentAccepted"
            payload = """{"paymentId":"p-2"}"""
            status = "SENT"
            attemptCount = 2
            this.createdAt = createdAt
            this.updatedAt = updatedAt
            this.sentAt = sentAt
            lastError = "previous attempt failed"
            synthetic = true
        }

        assertThat(entity.eventId).isEqualTo(eventId)
        assertThat(entity.aggregateId).isEqualTo(aggregateId)
        assertThat(entity.eventType).isEqualTo("PaymentAccepted")
        assertThat(entity.payload).isEqualTo("""{"paymentId":"p-2"}""")
        assertThat(entity.status).isEqualTo("SENT")
        assertThat(entity.attemptCount).isEqualTo(2)
        assertThat(entity.createdAt).isEqualTo(createdAt)
        assertThat(entity.updatedAt).isEqualTo(updatedAt)
        assertThat(entity.sentAt).isEqualTo(sentAt)
        assertThat(entity.lastError).isEqualTo("previous attempt failed")
        assertThat(entity.synthetic).isTrue()
    }

    @Test
    fun `unset required mapped properties fail rather than returning fabricated values`() {
        val entity = PanacheOutboxEntity()

        assertThatThrownBy { entity.eventId }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.aggregateId }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.eventType }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.payload }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.status }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.createdAt }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.updatedAt }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThat(entity.attemptCount).isZero()
        assertThat(entity.sentAt).isNull()
        assertThat(entity.lastError).isNull()
        assertThat(entity.synthetic).isFalse()
    }

    @Test
    fun `toEntry preserves the complete durable outbox state`() {
        val eventId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val createdAt = Instant.parse("2026-09-29T08:00:00Z")
        val updatedAt = Instant.parse("2026-09-29T08:01:00Z")
        val sentAt = Instant.parse("2026-09-29T08:02:00Z")
        val entity = PanacheOutboxEntity().apply {
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
            synthetic = true
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
                synthetic = true,
            ),
        )
    }

    @Test
    fun `toEntry rejects an unknown persisted status`() {
        val entity = PanacheOutboxEntity().apply {
            eventId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000002")
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
