// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.domain.model.Disclosure
import com.openbank.delegation.domain.model.DisclosureStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DisclosureSnapshotEventConsumerTest {
    @Test
    fun `ready outcome records only matching snapshot`(): Unit = runBlocking {
        val repository = mockk<DisclosureRepository>(relaxed = true)
        val requestId = UUID.randomUUID()
        val snapshotId = UUID.randomUUID()
        val sourceId = UUID.randomUUID()
        coEvery { repository.findByRequestId(requestId) } returns disclosure(requestId, snapshotId, sourceId)
        val mapper = jacksonObjectMapper()
        val consumer = DisclosureSnapshotEventConsumer(repository, mapper)

        consumer.consume(
            mapper.writeValueAsString(
                mapOf(
                    "eventType" to "DisclosureSnapshotReady",
                    "requestId" to requestId,
                    "snapshotId" to snapshotId,
                    "sourceDocumentId" to sourceId,
                    "sourceSha256" to "a".repeat(64),
                    "sha256" to "b".repeat(64),
                    "sizeBytes" to 42,
                    "occurredAt" to "2026-09-09T12:00:00Z",
                ),
            ),
        )

        coVerify { repository.markReady(requestId, snapshotId, sourceId, any(), any(), 42, any()) }
    }

    private fun disclosure(requestId: UUID, snapshotId: UUID, sourceId: UUID) = Disclosure(
        UUID.randomUUID(), requestId, UUID.randomUUID(), UUID.randomUUID(), sourceId,
        DisclosureStatus.READY, snapshotId, "a".repeat(64), "b".repeat(64), 42, null,
        Instant.parse("2026-09-09T12:00:00Z"), Instant.parse("2026-09-09T12:00:00Z"),
    )
}
