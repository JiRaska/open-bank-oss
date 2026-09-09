// SPDX-License-Identifier: Apache-2.0
package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.DisclosureSnapshotUseCase
import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.DocumentTemplateUseCase
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import com.openbank.document.domain.model.DisclosureSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class DisclosureSnapshotContentResourceTest {
    private val snapshots = mockk<DisclosureSnapshotUseCase>()
    private val resource = DocumentResource(
        mockk<DocumentTemplateUseCase>(),
        mockk<DocumentRenderUseCase>(),
        mockk<DocumentQueryUseCase>(),
        mockk<OnboardingDocumentUseCase>(),
        snapshots,
    )

    @Test
    fun `returns only digest-bound immutable snapshot bytes`(): Unit = runBlocking {
        val bytes = "%PDF sealed".toByteArray()
        val snapshot = snapshot()
        coEvery { snapshots.getMetadata(SNAPSHOT_ID) } returns snapshot
        coEvery { snapshots.getContent(SNAPSHOT_ID) } returns bytes

        val response = resource.getDisclosureSnapshotContent(SNAPSHOT_ID, DIGEST)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(bytes)
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("private, no-store")
        assertThat(response.getHeaderString("ETag")).isEqualTo("\"$DIGEST\"")
    }

    @Test
    fun `digest mismatch is indistinguishable from a missing snapshot`(): Unit = runBlocking {
        coEvery { snapshots.getMetadata(SNAPSHOT_ID) } returns snapshot()

        assertThrows<NotFoundException> {
            runBlocking { resource.getDisclosureSnapshotContent(SNAPSHOT_ID, "b".repeat(64)) }
        }
        coVerify(exactly = 0) { snapshots.getContent(any()) }
    }

    private fun snapshot() = DisclosureSnapshot(
        SNAPSHOT_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        "c".repeat(64),
        DIGEST,
        "opaque-storage-key",
        "application/pdf",
        11,
        Instant.parse("2026-09-09T12:00:00Z"),
    )

    private companion object {
        val SNAPSHOT_ID: UUID = UUID.randomUUID()
        val DIGEST: String = "a".repeat(64)
    }
}
