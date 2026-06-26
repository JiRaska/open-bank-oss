// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.consent.infrastructure.persistence.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ConsentOutboxEntityTest {

    @Test
    fun `field accessors hold the assigned values`() {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val createdAt = Instant.now()
        val updatedAt = createdAt.plusSeconds(1)
        val sentAt = createdAt.plusSeconds(2)

        val entity = ConsentOutboxEntity().apply {
            this.eventId = eventId
            this.aggregateId = aggregateId
            eventType = "ConsentGranted"
            payload = """{"k":"v"}"""
            status = "PENDING"
            attemptCount = 3
            this.sentAt = sentAt
            lastError = "boom"
            this.createdAt = createdAt
            this.updatedAt = updatedAt
        }

        assertThat(entity.eventId).isEqualTo(eventId)
        assertThat(entity.aggregateId).isEqualTo(aggregateId)
        assertThat(entity.eventType).isEqualTo("ConsentGranted")
        assertThat(entity.payload).isEqualTo("""{"k":"v"}""")
        assertThat(entity.status).isEqualTo("PENDING")
        assertThat(entity.attemptCount).isEqualTo(3)
        assertThat(entity.sentAt).isEqualTo(sentAt)
        assertThat(entity.lastError).isEqualTo("boom")
        assertThat(entity.createdAt).isEqualTo(createdAt)
        assertThat(entity.updatedAt).isEqualTo(updatedAt)
    }

    @Test
    fun `nullable fields default to null and attemptCount defaults to zero`() {
        val entity = ConsentOutboxEntity()

        assertThat(entity.sentAt).isNull()
        assertThat(entity.lastError).isNull()
        assertThat(entity.attemptCount).isZero()
    }
}
