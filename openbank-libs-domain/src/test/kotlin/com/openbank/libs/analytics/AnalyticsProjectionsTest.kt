// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AnalyticsProjectionsTest {

    private fun env(
        eventId: UUID = UUID.randomUUID(),
        aggregateType: String = "ACCOUNT",
        aggregateId: String = "acc-1",
        version: Long = 0,
        occurredAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        ingestedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) = AnalyticsEnvelope(
        eventId = eventId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        aggregateVersion = version,
        eventType = "account.changed",
        occurredAt = occurredAt,
        sourceService = "openbank-account-service",
        schemaVersion = 1,
        ingestedAt = ingestedAt,
    )

    @Test
    fun `dedupeByEventId drops at-least-once duplicates keeping first occurrence`() {
        val id = UUID.randomUUID()
        val first = env(eventId = id, version = 1)
        val dupe = env(eventId = id, version = 1)
        val other = env(eventId = UUID.randomUUID(), version = 2)

        val result = AnalyticsProjections.dedupeByEventId(listOf(first, dupe, other))

        assertThat(result).containsExactly(first, other)
    }

    @Test
    fun `dedupeByEventId preserves input order`() {
        val a = env(eventId = UUID.randomUUID())
        val b = env(eventId = UUID.randomUUID())
        val c = env(eventId = UUID.randomUUID())

        assertThat(AnalyticsProjections.dedupeByEventId(listOf(c, a, b)))
            .containsExactly(c, a, b)
    }

    @Test
    fun `latestPerAggregate keeps the highest version per aggregate`() {
        val v1 = env(aggregateId = "acc-1", version = 1)
        val v3 = env(aggregateId = "acc-1", version = 3)
        val v2 = env(aggregateId = "acc-1", version = 2)

        val result = AnalyticsProjections.latestPerAggregate(listOf(v1, v3, v2))

        assertThat(result).containsExactly(v3)
    }

    @Test
    fun `latestPerAggregate is order-independent for out-of-order arrival`() {
        val v1 = env(aggregateId = "acc-1", version = 1)
        val v2 = env(aggregateId = "acc-1", version = 2)

        val forward = AnalyticsProjections.latestPerAggregate(listOf(v1, v2))
        val reverse = AnalyticsProjections.latestPerAggregate(listOf(v2, v1))

        assertThat(forward).containsExactly(v2)
        assertThat(reverse).containsExactly(v2)
    }

    @Test
    fun `latestPerAggregate breaks version ties by occurredAt then ingestedAt`() {
        val older = env(version = 5, occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val newer = env(version = 5, occurredAt = Instant.parse("2026-02-01T00:00:00Z"))

        assertThat(AnalyticsProjections.latestPerAggregate(listOf(older, newer)))
            .containsExactly(newer)
    }

    @Test
    fun `latestPerAggregate separates distinct aggregates and types`() {
        val acc = env(aggregateType = "ACCOUNT", aggregateId = "1", version = 1)
        val party = env(aggregateType = "PARTY", aggregateId = "1", version = 1)
        val acc2 = env(aggregateType = "ACCOUNT", aggregateId = "2", version = 1)

        assertThat(AnalyticsProjections.latestPerAggregate(listOf(acc, party, acc2)))
            .containsExactlyInAnyOrder(acc, party, acc2)
    }

    @Test
    fun `BRONZE_MINIMUM retention floor is at least 10 years`() {
        assertThat(AnalyticsRetention.BRONZE_MINIMUM.years).isGreaterThanOrEqualTo(10)
    }

    @Test
    fun `asOf restricts current state to events at or before the cut-off`() {
        val v1 = env(aggregateId = "acc-1", version = 1, occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val v2 = env(aggregateId = "acc-1", version = 2, occurredAt = Instant.parse("2026-03-01T00:00:00Z"))

        val asOfFeb = AnalyticsProjections.asOf(listOf(v1, v2), Instant.parse("2026-02-01T00:00:00Z"))
        val asOfApr = AnalyticsProjections.asOf(listOf(v1, v2), Instant.parse("2026-04-01T00:00:00Z"))

        assertThat(asOfFeb).containsExactly(v1)
        assertThat(asOfApr).containsExactly(v2)
    }

    @Test
    fun `history returns one aggregate's events oldest-to-newest`() {
        val v2 = env(aggregateId = "acc-1", version = 2)
        val v1 = env(aggregateId = "acc-1", version = 1)
        val other = env(aggregateId = "acc-2", version = 9)

        val history = AnalyticsProjections.history(listOf(v2, v1, other), "ACCOUNT", "acc-1")

        assertThat(history).containsExactly(v1, v2)
    }

    @Test
    fun `default ingestSource is STREAM and batchId null`() {
        val e = env()
        assertThat(e.ingestSource).isEqualTo(IngestSource.STREAM)
        assertThat(e.batchId).isNull()
    }

    @Test
    fun `Reconciliation diff flags missing-in-warehouse, orphans and version drift`() {
        val k1 = AggregateKey("ACCOUNT", "1")
        val k2 = AggregateKey("ACCOUNT", "2")
        val k3 = AggregateKey("ACCOUNT", "3")
        val source = mapOf(k1 to 5L, k2 to 7L) // k1 in sync, k2 behind in warehouse
        val warehouse = mapOf(k1 to 5L, k2 to 6L, k3 to 1L) // k3 orphan

        val diff = Reconciliation.diff(source, warehouse)

        assertThat(diff.versionMismatch).containsExactly(k2)
        assertThat(diff.missingInSource).containsExactly(k3)
        assertThat(diff.missingInWarehouse).isEmpty()
        assertThat(diff.inSync).isFalse()
        assertThat(diff.status).isEqualTo("DRIFT")
    }

    @Test
    fun `Reconciliation diff is IN_SYNC when both sides match`() {
        val k = AggregateKey("PARTY", "p-1")
        val diff = Reconciliation.diff(mapOf(k to 3L), mapOf(k to 3L))
        assertThat(diff.inSync).isTrue()
        assertThat(diff.driftCount).isEqualTo(0)
    }

    @Test
    fun `versionMap reduces a batch to per-aggregate max version`() {
        val v1 = env(aggregateId = "acc-1", version = 1)
        val v3 = env(aggregateId = "acc-1", version = 3)
        assertThat(Reconciliation.versionMap(listOf(v1, v3)))
            .containsEntry(AggregateKey("ACCOUNT", "acc-1"), 3L)
    }

    @Test
    fun `BackfillPlanner chunks a window into bounded sub-windows covering the full range`() {
        val req = BackfillRequest(
            source = IngestSource.BACKFILL,
            from = Instant.parse("2026-01-01T00:00:00Z"),
            to = Instant.parse("2026-01-04T00:00:00Z"),
            reason = "outage gap",
            requestedBy = "ops-1",
        )

        val windows = BackfillPlanner.chunk(req, java.time.Duration.ofDays(1))

        assertThat(windows).hasSize(3)
        assertThat(windows.first().from).isEqualTo(req.from)
        assertThat(windows.last().to).isEqualTo(req.to)
    }

    @Test
    fun `BackfillRequest rejects STREAM as a backfill source`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BackfillRequest(
                source = IngestSource.STREAM,
                from = Instant.parse("2026-01-01T00:00:00Z"),
                to = Instant.parse("2026-01-02T00:00:00Z"),
                reason = "x",
                requestedBy = "y",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
