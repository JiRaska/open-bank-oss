// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AgentAuditHistoricalReplayTest {
    private val store = mockk<AgentAuditReplayStore>()
    private val connection = mockk<Connection>()
    private val emitter = mockk<Emitter<Record<String, String>>>()
    private val emitterInstance = mockk<Instance<Emitter<Record<String, String>>>>()
    private val campaignId = UUID.randomUUID()
    private val from = Instant.parse("2025-01-01T00:00:00Z")
    private val until = Instant.parse("2025-01-02T00:00:00Z")
    private val eventId = UUID.randomUUID()
    private val payload = "{\"eventId\":\"$eventId\",\"sourceService\":\"agent-service\"}"
    private val row = AgentAuditReplayStore.Row(from.plusSeconds(1), eventId, payload)
    private val manifest = AgentAuditReplayStore.sha256(
        "${row.createdAt}:${row.eventId}:${AgentAuditReplayStore.sha256(payload)}\n",
    )

    private fun replay(
        execute: Boolean,
        transportEnabled: Boolean = true,
        maxEvents: Int = 25,
        expectedCount: Optional<Int> = Optional.of(1),
        expectedManifest: Optional<String> = Optional.of(manifest),
    ): AgentAuditHistoricalReplay = AgentAuditHistoricalReplay(
        store,
        jacksonObjectMapper(),
        emitterInstance,
        true,
        execute,
        transportEnabled,
        Optional.of(campaignId.toString()),
        Optional.of(from.toString()),
        Optional.of(until.toString()),
        maxEvents,
        expectedCount,
        expectedManifest,
    )

    private fun mockSingleRowInventory() {
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(1, 0)
        every { store.next(connection, any(), 1) } returns listOf(row)
    }

    @Test
    fun `execute without both approved inputs never creates a campaign or sends`(): Unit = runBlocking {
        mockSingleRowInventory()

        assertThatThrownBy {
            runBlocking { replay(execute = true, expectedCount = Optional.empty()).replay() }
        }.hasMessageContaining("expected count is required")
        assertThatThrownBy {
            runBlocking { replay(execute = true, expectedManifest = Optional.empty()).replay() }
        }.hasMessageContaining("expected manifest SHA-256 is required")

        verify(exactly = 0) { store.campaign(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { store.checkpoint(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { emitterInstance.get() }
    }

    @Test
    fun `execute rejects mismatched count malformed digest and mismatched digest before writes`(): Unit = runBlocking {
        mockSingleRowInventory()

        assertThatThrownBy {
            runBlocking { replay(execute = true, expectedCount = Optional.of(2)).replay() }
        }.hasMessageContaining("does not match approved")
        assertThatThrownBy {
            runBlocking { replay(execute = true, expectedManifest = Optional.of("not-a-digest")).replay() }
        }.hasMessageContaining("is invalid")
        assertThatThrownBy {
            runBlocking { replay(execute = true, expectedManifest = Optional.of("0".repeat(64))).replay() }
        }.hasMessageContaining("does not match approved")

        verify(exactly = 0) { store.campaign(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { store.checkpoint(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { emitterInstance.get() }
    }

    @Test
    fun `dry run reads bounded rows without checkpoint or broker access`(): Unit = runBlocking {
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(1, 0)
        every { store.next(connection, any(), 1) } returns listOf(row)

        replay(execute = false).replay()

        verify(exactly = 0) { store.campaign(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { store.checkpoint(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { emitterInstance.get() }
    }

    @Test
    fun `dry run validates beyond the first page and rejects a late malformed row`(): Unit = runBlocking {
        val rows = (1..26).map { index ->
            val id = UUID.randomUUID()
            AgentAuditReplayStore.Row(
                from.plusSeconds(index.toLong()),
                id,
                "{\"eventId\":\"$id\",\"sourceService\":\"agent-service\"}",
            )
        }
        val malformed = rows.last().copy(payload = rows.last().payload.replace("agent-service", "wrong-source"))
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(26, 0)
        every { store.next(connection, match { it.cursor == null }, 25) } returns rows.take(25)
        every { store.next(connection, match { it.cursor?.eventId == rows[24].eventId }, 1) } returns
            listOf(malformed)

        assertThatThrownBy { runBlocking { replay(execute = false, maxEvents = 26).replay() } }
            .hasMessageContaining("source service mismatch")

        verify(exactly = 0) { store.campaign(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { emitterInstance.get() }
    }

    @Test
    fun `unpublished row in fixed window blocks campaign creation`(): Unit = runBlocking {
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(2, 1)

        assertThatThrownBy { runBlocking { replay(execute = true).replay() } }
            .hasMessageContaining("unpublished rows")

        verify(exactly = 0) { store.campaign(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { emitterInstance.get() }
    }

    @Test
    fun `broker rejection leaves durable cursor unchanged for crash retry`(): Unit = runBlocking {
        val campaign = AgentAuditReplayStore.Campaign(campaignId, from, until, 25, 1, "manifest", null, 0)
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(1, 0)
        every { store.campaign(connection, campaignId, from, until, 25, 1, any()) } returns campaign
        every { store.next(connection, any(), 1) } returns listOf(row)
        every { store.verifyCursor(connection, null) } returns Unit
        every { store.reserveBatch(connection, campaignId) } returns true
        every { store.next(connection, campaign, 25) } returns listOf(row)
        every { emitterInstance.get() } returns emitter
        every { emitter.send(match { it.key() == eventId.toString() && it.value() == payload }) } returns
            CompletableFuture.failedFuture(IllegalStateException("broker rejected"))

        assertThatThrownBy { runBlocking { replay(execute = true).replay() } }
            .hasRootCauseMessage("broker rejected")

        verify(exactly = 0) { store.checkpoint(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `acknowledged original payload advances cursor exactly once`(): Unit = runBlocking {
        val campaign = AgentAuditReplayStore.Campaign(campaignId, from, until, 25, 1, "manifest", null, 0)
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(1, 0)
        every { store.campaign(connection, campaignId, from, until, 25, 1, any()) } returns campaign
        every { store.next(connection, any(), 1) } returns listOf(row)
        every { store.verifyCursor(connection, null) } returns Unit
        every { store.reserveBatch(connection, campaignId) } returns true
        every { store.next(connection, campaign, 25) } returns listOf(row)
        every { emitterInstance.get() } returns emitter
        every { emitter.send(match { it.key() == eventId.toString() && it.value() == payload }) } returns
            CompletableFuture.completedFuture(null)
        every { store.checkpoint(connection, campaign, null, row, 25) } returns Unit

        replay(execute = true).replay()

        verify(exactly = 1) { store.checkpoint(connection, campaign, null, row, 25) }
    }

    @Test
    fun `checkpoint failure after broker ACK retries the identical event`(): Unit = runBlocking {
        val campaign = AgentAuditReplayStore.Campaign(campaignId, from, until, 25, 1, "manifest", null, 0)
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(1, 0)
        every { store.campaign(connection, campaignId, from, until, 25, 1, any()) } returns campaign
        every { store.next(connection, any(), 1) } returns listOf(row)
        every { store.verifyCursor(connection, null) } returns Unit
        every { store.reserveBatch(connection, campaignId) } returns true
        every { store.next(connection, campaign, 25) } returns listOf(row)
        every { emitterInstance.get() } returns emitter
        every { emitter.send(match { it.key() == eventId.toString() && it.value() == payload }) } returns
            CompletableFuture.completedFuture(null)
        every {
            store.checkpoint(connection, campaign, null, row, 25)
        } throws IllegalStateException("checkpoint unavailable")

        repeat(2) {
            assertThatThrownBy { runBlocking { replay(execute = true).replay() } }
                .hasMessageContaining("checkpoint unavailable")
        }

        verify(exactly = 2) { emitter.send(match { it.key() == eventId.toString() && it.value() == payload }) }
    }

    @Test
    fun `source mismatch fails closed before any send or checkpoint`(): Unit = runBlocking {
        val campaign = AgentAuditReplayStore.Campaign(campaignId, from, until, 25, 1, "manifest", null, 0)
        every { store.acquire() } returns connection
        every { store.release(connection) } returns Unit
        every { store.inventory(connection, from, until) } returns AgentAuditReplayStore.Inventory(1, 0)
        every { store.campaign(connection, campaignId, from, until, 25, 1, any()) } returns campaign
        every { store.next(connection, any(), 1) } returnsMany listOf(
            listOf(row),
            listOf(row.copy(payload = payload.replace("agent-service", "another-service"))),
        )
        every { store.verifyCursor(connection, null) } returns Unit
        every { store.reserveBatch(connection, campaignId) } returns true

        assertThatThrownBy { runBlocking { replay(execute = true).replay() } }
            .hasMessageContaining("source service mismatch")

        verify(exactly = 0) { emitterInstance.get() }
        verify(exactly = 0) { store.checkpoint(any(), any(), any(), any(), any()) }
    }
}
