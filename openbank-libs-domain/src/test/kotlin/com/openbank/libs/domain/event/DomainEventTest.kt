// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [DomainEvent] mints its own `eventId` — the dedupe key the analytics bronze layer and every
 * at-least-once Kafka consumer key on. A shared or stable id across instances would collapse
 * distinct events into one row, so the per-instance freshness is the property worth pinning.
 *
 * `occurredAt` is BUSINESS time supplied by the subclass. It is asserted here by recency, never by
 * non-nullity: an `Instant.EPOCH` default satisfies `isNotNull()` and has shipped in this fleet
 * before (`AuditEvent.timestamp`, `FlagExposure.timestamp`).
 */
class DomainEventTest {

    private data class AccountOpened(
        override val aggregateId: UUID,
        override val occurredAt: Instant,
    ) : DomainEvent(occurredAt) {
        override val aggregateType: String = "ACCOUNT"
        override val eventType: String = "account.opened"
        override val version: Long = 1
    }

    @Test
    fun `each instance mints its own event id`() {
        val id = UUID.randomUUID()
        val at = Instant.now()
        val ids = (1..50).map { AccountOpened(id, at).eventId }
        assertThat(ids.toSet()).hasSize(50)
    }

    @Test
    fun `the event id is stable for the life of one instance`() {
        val event = AccountOpened(UUID.randomUUID(), Instant.now())
        assertThat(event.eventId).isEqualTo(event.eventId)
    }

    @Test
    fun `an event stamped at construction carries a recent business time, not the epoch`() {
        val before = Instant.now()
        val event = AccountOpened(UUID.randomUUID(), Instant.now())
        assertThat(event.occurredAt).isBetween(before, Instant.now())
        assertThat(event.occurredAt).isNotEqualTo(Instant.EPOCH)
    }

    @Test
    fun `a replayed event keeps the historic business time the subclass supplied`() {
        val historic = Instant.parse("2019-03-04T05:06:07Z")
        assertThat(AccountOpened(UUID.randomUUID(), historic).occurredAt).isEqualTo(historic)
    }

    @Test
    fun `the aggregate coordinates the outbox and warehouse key on are all present`() {
        val aggregateId = UUID.randomUUID()
        val event: DomainEvent = AccountOpened(aggregateId, Instant.now())
        assertThat(event.aggregateId).isEqualTo(aggregateId)
        assertThat(event.aggregateType).isEqualTo("ACCOUNT")
        assertThat(event.eventType).isEqualTo("account.opened")
        assertThat(event.version).isEqualTo(1L)
    }

    @Test
    fun `data-class equality ignores the generated event id, so it must not be used as identity`() {
        val id = UUID.randomUUID()
        val at = Instant.now()
        val a = AccountOpened(id, at)
        val b = AccountOpened(id, at)
        assertThat(a).isEqualTo(b)
        assertThat(a.eventId).isNotEqualTo(b.eventId)
    }
}
