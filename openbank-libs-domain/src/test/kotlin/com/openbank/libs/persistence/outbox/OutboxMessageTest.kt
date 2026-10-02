// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The defaults on [OutboxMessage] are load-bearing, not cosmetic. `createdAt` defaulting to
 * `Instant.EPOCH` stamped 388 of ledger's 553 outbox rows with 1970 (#3272): the dispatcher claims
 * `ORDER BY created_at ASC`, so epoch rows sort ahead of live traffic forever and the backlog-age
 * signal reads a fresh row as decades old. So this asserts RECENCY, never non-nullity — an
 * `isNotNull()` here would pass happily against the exact defect it is meant to catch.
 */
class OutboxMessageTest {

    private val aggregate: UUID = UUID.randomUUID()

    @Test
    fun `createdAt defaults to now, not the epoch`() {
        val before = Instant.now()
        val msg = OutboxMessage(aggregateId = aggregate, eventType = "account.created", payload = "{}")
        val after = Instant.now()

        assertThat(msg.createdAt).isBetween(before, after)
        assertThat(msg.createdAt).isNotEqualTo(Instant.EPOCH)
    }

    @Test
    fun `a caller with a fixed clock can still pin createdAt exactly`() {
        val pinned = Instant.parse("2020-01-02T03:04:05Z")
        val msg = OutboxMessage(
            aggregateId = aggregate,
            eventType = "account.created",
            payload = "{}",
            createdAt = pinned,
        )
        assertThat(msg.createdAt).isEqualTo(pinned)
    }

    @Test
    fun `eventId defaults to a fresh unique id per message`() {
        val ids = (1..50).map {
            OutboxMessage(aggregateId = aggregate, eventType = "e", payload = "{}").eventId
        }
        assertThat(ids.toSet()).hasSize(50)
    }

    @Test
    fun `synthetic origin defaults to false so real traffic is never mislabelled`() {
        val msg = OutboxMessage(aggregateId = aggregate, eventType = "e", payload = "{}")
        assertThat(msg.synthetic).isFalse()
        assertThat(
            OutboxMessage(aggregateId = aggregate, eventType = "e", payload = "{}", synthetic = true).synthetic,
        ).isTrue()
    }

    @Test
    fun `two messages built with the same explicit fields are equal, differing only by generated ids`() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val id = UUID.randomUUID()
        val a = OutboxMessage(id, aggregate, "e", "{}", at)
        val b = OutboxMessage(id, aggregate, "e", "{}", at)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(a.copy(eventId = UUID.randomUUID()))
    }

    @Test
    fun `the lifecycle enum carries exactly the five documented statuses`() {
        assertThat(OutboxStatus.entries).containsExactly(
            OutboxStatus.PENDING,
            OutboxStatus.DISPATCHING,
            OutboxStatus.SENT,
            OutboxStatus.FAILED,
            OutboxStatus.DEAD,
        )
    }

    @Test
    fun `an OutboxEntry keeps sentAt and lastError absent until the row is decided`() {
        val now = Instant.now()
        val entry = OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = aggregate,
            eventType = "e",
            payload = "{}",
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = now,
            updatedAt = now,
            sentAt = null,
            lastError = null,
        )
        assertThat(entry.sentAt).isNull()
        assertThat(entry.lastError).isNull()
        assertThat(entry.synthetic).isFalse()
        assertThat(entry.attemptCount).isZero()
    }
}
