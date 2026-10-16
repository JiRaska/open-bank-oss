// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Public entity accessors must retain values and reject absent required fields. */
@Suppress("DEPRECATION")
class OutboxEntityPropertyTest {
    private val event = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val aggregate = UUID.fromString("00000000-0000-0000-0000-000000000022")
    private val created = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `active record accessors retain assigned values and provenance`() {
        val entity = PanacheOutboxEntity().apply {
            eventId = event
            aggregateId = aggregate
            eventType = "test.changed"
            payload = "test payload"
            status = "FAILED"
            createdAt = created
            updatedAt = created.plusSeconds(7)
        }
        assertThat(entity.eventId).isEqualTo(event)
        assertThat(entity.aggregateId).isEqualTo(aggregate)
        assertThat(entity.eventType).isEqualTo("test.changed")
        assertThat(entity.payload).isEqualTo("test payload")
        assertThat(entity.status).isEqualTo("FAILED")
        assertThat(entity.createdAt).isEqualTo(created)
        assertThat(entity.updatedAt).isEqualTo(created.plusSeconds(7))
        assertThat(entity.synthetic).isFalse()
        entity.synthetic = true
        assertThat(entity.synthetic).isTrue()
    }

    @Test
    fun `repository entity accessors retain assigned optional and required values`() {
        val entity = object : AbstractOutboxEntity() {}.apply {
            eventId = event
            aggregateId = aggregate
            eventType = "test.changed"
            payload = "test payload"
            status = "FAILED"
            attemptCount = 4
            createdAt = created
            updatedAt = created.plusSeconds(7)
            sentAt = created.plusSeconds(9)
            lastError = "retryable"
        }
        assertThat(entity.eventId).isEqualTo(event)
        assertThat(entity.aggregateId).isEqualTo(aggregate)
        assertThat(entity.eventType).isEqualTo("test.changed")
        assertThat(entity.payload).isEqualTo("test payload")
        assertThat(entity.status).isEqualTo("FAILED")
        assertThat(entity.attemptCount).isEqualTo(4)
        assertThat(entity.createdAt).isEqualTo(created)
        assertThat(entity.updatedAt).isEqualTo(created.plusSeconds(7))
        assertThat(entity.sentAt).isEqualTo(created.plusSeconds(9))
        assertThat(entity.lastError).isEqualTo("retryable")
    }

    @Test
    fun `uninitialized required active record properties fail explicitly`() {
        val entity = PanacheOutboxEntity()
        listOf<() -> Any?>(
            { entity.eventId },
            { entity.aggregateId },
            { entity.eventType },
            { entity.payload },
            { entity.status },
            { entity.createdAt },
            { entity.updatedAt },
        ).forEach { getter ->
            assertThatThrownBy { getter() }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        }
    }

    @Test
    fun `uninitialized required repository entity properties fail explicitly`() {
        val entity = object : AbstractOutboxEntity() {}
        listOf<() -> Any?>(
            { entity.eventId },
            { entity.aggregateId },
            { entity.eventType },
            { entity.payload },
            { entity.status },
            { entity.createdAt },
            { entity.updatedAt },
        ).forEach { getter ->
            assertThatThrownBy { getter() }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        }
    }
}
