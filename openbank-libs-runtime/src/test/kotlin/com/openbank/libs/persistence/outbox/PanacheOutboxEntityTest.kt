// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the [PanacheOutboxEntity] mapped superclass and its ADR-0327 D1 [PanacheOutboxEntityV2]
 * extension: column defaults, the lateinit guards, and the `toEntry()` projection every dispatcher
 * consumes. These run without Hibernate — a plain instance carries no persistence state, which is
 * exactly why pitest saw the class as uncovered: the only tests that touched it were per-service
 * @QuarkusTest ITs, which the PIT runner never executes.
 */
class PanacheOutboxEntityTest {

    private val eventId = UUID.fromString("3f0a62d4-1c3a-4e2b-9d50-2c1e5f6a7b8c")
    private val aggregateId = UUID.fromString("8b7c9d0e-1f2a-4a5b-8c6d-7e8f9a0b1c2d")
    private val createdAt = Instant.parse("2026-10-14T12:00:00Z")
    private val updatedAt = Instant.parse("2026-10-14T12:00:30Z")

    private fun filledEntity(): PanacheOutboxEntity = PanacheOutboxEntity().apply {
        eventId = this@PanacheOutboxEntityTest.eventId
        aggregateId = this@PanacheOutboxEntityTest.aggregateId
        eventType = "payment.initiated"
        payload = "{\"amount\":\"10.50\"}"
        status = "PENDING"
        createdAt = this@PanacheOutboxEntityTest.createdAt
        updatedAt = this@PanacheOutboxEntityTest.updatedAt
    }

    @Test
    fun `mutable columns carry their fleet defaults`() {
        val entity = PanacheOutboxEntity()
        assertThat(entity.attemptCount).isZero()
        assertThat(entity.synthetic).isFalse()
        assertThat(entity.sentAt).isNull()
        assertThat(entity.lastError).isNull()
    }

    @Test
    fun `unset lateinit columns fail fast instead of reading as null`() {
        val entity = PanacheOutboxEntity()
        // A lateinit guard that a mutant disables returns null here and toEntry() then NPEs far
        // from the cause — the guard is the behaviour under test.
        assertThatThrownBy { entity.eventId }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.aggregateId }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.eventType }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.payload }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.status }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.createdAt }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { entity.updatedAt }.isInstanceOf(UninitializedPropertyAccessException::class.java)
    }

    @Test
    fun `toEntry projects every column including the ADR-0252 synthetic flag`() {
        val entity = filledEntity().apply {
            attemptCount = 3
            sentAt = Instant.parse("2026-10-14T12:05:00Z")
            lastError = "broker unreachable"
            synthetic = true
        }

        // Read the lateinit columns through their getters too: toEntry() reads fields directly,
        // so without these the getters themselves stay unexecuted.
        assertThat(entity.eventType).isEqualTo("payment.initiated")
        assertThat(entity.payload).isEqualTo("{\"amount\":\"10.50\"}")
        assertThat(entity.status).isEqualTo("PENDING")

        val entry = entity.toEntry()

        assertThat(entry.eventId).isEqualTo(eventId)
        assertThat(entry.aggregateId).isEqualTo(aggregateId)
        assertThat(entry.eventType).isEqualTo("payment.initiated")
        assertThat(entry.payload).isEqualTo("{\"amount\":\"10.50\"}")
        assertThat(entry.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(entry.attemptCount).isEqualTo(3)
        assertThat(entry.createdAt).isEqualTo(createdAt)
        assertThat(entry.updatedAt).isEqualTo(updatedAt)
        assertThat(entry.sentAt).isEqualTo(Instant.parse("2026-10-14T12:05:00Z"))
        assertThat(entry.lastError).isEqualTo("broker unreachable")
        assertThat(entry.synthetic).isTrue()
    }

    @Test
    fun `the v2 entity adds the claim columns and folds them into toEntry`() {
        val entity = PanacheOutboxEntityV2().apply {
            eventId = this@PanacheOutboxEntityTest.eventId
            aggregateId = this@PanacheOutboxEntityTest.aggregateId
            eventType = "payment.initiated"
            payload = "{}"
            status = "FAILED"
            createdAt = this@PanacheOutboxEntityTest.createdAt
            updatedAt = this@PanacheOutboxEntityTest.updatedAt
        }

        assertThat(entity.claimedAt).isNull()
        assertThat(entity.nextAttemptAt).isNull()
        assertThat(entity.toEntry().status).isEqualTo(OutboxStatus.FAILED)
        assertThat(entity.toEntry().claimedAt).isNull()

        entity.claimedAt = Instant.parse("2026-10-14T12:01:00Z")
        entity.nextAttemptAt = Instant.parse("2026-10-14T12:02:00Z")
        assertThat(entity.toEntry().claimedAt).isEqualTo(Instant.parse("2026-10-14T12:01:00Z"))
        assertThat(entity.toEntry().nextAttemptAt).isEqualTo(Instant.parse("2026-10-14T12:02:00Z"))
    }
}
