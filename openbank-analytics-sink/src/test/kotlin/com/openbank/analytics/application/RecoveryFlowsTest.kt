// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.analytics.application.port.out.BackfillSource
import com.openbank.analytics.application.port.out.DeadLetterRecord
import com.openbank.analytics.application.port.out.DeadLetterSink
import com.openbank.analytics.application.port.out.DurableBackfillUnavailableException
import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.WormArchive
import com.openbank.analytics.infrastructure.reconcile.NoOpBackfillSource
import com.openbank.libs.analytics.AnalyticsEnvelope
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.BackfillWindow
import com.openbank.libs.analytics.IngestSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Covers the recovery flows that the live stream alone cannot: poison-message quarantine (DLQ) and
 * backfill/correction reloads (idempotent dedupe + lineage tagging). Plain JUnit + hand-rolled fakes,
 * no @QuarkusTest boot.
 */
class RecoveryFlowsTest {

    private val mapper = ObjectMapper()

    private class CapturingSink : AnalyticsSink {
        val written = mutableListOf<AnalyticsEnvelope>()
        override suspend fun write(envelope: AnalyticsEnvelope) {
            written += envelope
        }
    }

    private class CapturingDlq : DeadLetterSink {
        val records = mutableListOf<DeadLetterRecord>()
        override suspend fun quarantine(record: DeadLetterRecord) {
            records += record
        }
    }

    private class FakeBackfillSource(private val payloads: List<String>) : BackfillSource {
        override suspend fun read(window: BackfillWindow, request: BackfillRequest): List<String> = payloads
    }

    private class CapturingWorm : WormArchive {
        val anchors = mutableListOf<IntegrityAnchor>()
        override suspend fun seal(anchor: IntegrityAnchor) {
            anchors += anchor
        }
        override suspend fun latest(): IntegrityAnchor? = anchors.lastOrNull()
    }

    @Test
    fun `malformed message is quarantined to the DLQ, not silently dropped`() = runBlocking<Unit> {
        val sink = CapturingSink()
        val dlq = CapturingDlq()
        val consumer = AnalyticsConsumer().apply {
            this.sink = sink
            this.deadLetters = dlq
            objectMapper = mapper
            clock = Clock.systemUTC()
        }

        consumer.consume(Message.of("{ this is not valid json"))

        assertThat(sink.written).isEmpty()
        assertThat(dlq.records).hasSize(1)
        assertThat(dlq.records.single().contentHash).isNotBlank()
        assertThat(dlq.records.single().rawPayload).contains("not valid json")
    }

    @Test
    fun `DLQ content hash is stable so re-delivery is idempotent`() = runBlocking<Unit> {
        val dlq = CapturingDlq()
        val consumer = AnalyticsConsumer().apply {
            sink = CapturingSink()
            deadLetters = dlq
            objectMapper = mapper
            clock = Clock.systemUTC()
        }

        consumer.consume(Message.of("broken"))
        consumer.consume(Message.of("broken"))

        assertThat(dlq.records.map { it.contentHash }.distinct()).hasSize(1)
    }

    @Test
    fun `unconfigured durable backfill fails closed instead of reporting an empty success`() = runBlocking<Unit> {
        val request = BackfillRequest(
            source = IngestSource.BACKFILL,
            from = Instant.parse("2026-01-01T00:00:00Z"),
            to = Instant.parse("2026-01-01T01:00:00Z"),
            reason = "replay outage gap",
            requestedBy = "ops-1",
        )

        assertThat(
            runCatching {
                NoOpBackfillSource().read(
                    BackfillWindow(request.from, request.to),
                    request,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(DurableBackfillUnavailableException::class.java)
    }

    @Test
    fun `backfill dedupes by eventId and tags every row with source and batchId`() = runBlocking<Unit> {
        val idX = UUID.randomUUID()
        val idY = UUID.randomUUID()
        fun event(id: UUID, aggId: String) = """{ "eventId":"$id", "aggregateType":"ACCOUNT",
            "aggregateId":"$aggId", "aggregateVersion":1,
            "eventType":"account.changed", "sourceService":"svc" }"""

        val sink = CapturingSink()
        val worm = CapturingWorm()
        val service = BackfillService().apply {
            source = FakeBackfillSource(listOf(event(idX, "a"), event(idX, "a"), event(idY, "b")))
            this.sink = sink
            this.worm = worm
            consumer = AnalyticsConsumer().apply {
                objectMapper = mapper
                clock = Clock.systemUTC()
            }
            objectMapper = mapper
            chunk = Duration.ofDays(1)
            clock = Clock.systemUTC()
        }

        val report = service.run(
            BackfillRequest(
                source = IngestSource.BACKFILL,
                from = Instant.parse("2026-01-01T00:00:00Z"),
                to = Instant.parse("2026-01-01T06:00:00Z"),
                reason = "outage gap 2026-01-01",
                requestedBy = "ops-1",
            ),
        )

        // Duplicate eventId collapsed; both surviving rows carry the batch lineage.
        assertThat(report.ingested).isEqualTo(2)
        assertThat(report.deduped).isEqualTo(1)
        assertThat(sink.written).hasSize(2)
        assertThat(sink.written).allMatch { it.ingestSource == IngestSource.BACKFILL }
        assertThat(sink.written).allMatch { it.batchId == report.batchId }
        assertThat(report.status).isEqualTo("COMPLETED")

        // F1+F2: a tamper-evidence anchor is sealed for the batch (Merkle root over the surviving rows).
        assertThat(worm.anchors).hasSize(1)
        assertThat(worm.anchors.single().recordCount).isEqualTo(2)
        assertThat(worm.anchors.single().merkleRoot).isNotBlank()
        assertThat(worm.anchors.single().anchorId).isEqualTo(report.batchId)
    }

    @Test
    fun `maker-checker requires a different approver and only executes once approved`() = runBlocking<Unit> {
        val sink = CapturingSink()
        val worm = CapturingWorm()
        val backfill = BackfillService().apply {
            source = FakeBackfillSource(emptyList())
            this.sink = sink
            this.worm = worm
            consumer = AnalyticsConsumer().apply {
                objectMapper = mapper
                clock = Clock.systemUTC()
            }
            objectMapper = mapper
            chunk = Duration.ofDays(1)
            clock = Clock.systemUTC()
        }
        val service = SensitiveReloadService().apply {
            store = com.openbank.analytics.infrastructure.proposal.InMemoryProposalStore()
            clock = Clock.systemUTC()
            this.backfill = backfill
        }

        val proposal = service.propose(
            BackfillRequest(
                source = IngestSource.CORRECTION,
                from = Instant.parse("2026-01-01T00:00:00Z"),
                to = Instant.parse("2026-01-02T00:00:00Z"),
                reason = "restate fees",
                requestedBy = "alice",
            ),
        )

        // Cannot execute before approval.
        assertThat(runCatching { service.execute(proposal.id) }.isFailure).isTrue()
        // Self-approval rejected (four-eyes).
        assertThat(runCatching { service.approve(proposal.id, "alice", null) }.isFailure).isTrue()

        // A different operator approves, then it executes exactly once.
        service.approve(proposal.id, "bob", "looks right")
        val report = service.execute(proposal.id)
        assertThat(report.status).isEqualTo("COMPLETED")
        assertThat(runCatching { service.execute(proposal.id) }.isFailure).isTrue()
    }
}
