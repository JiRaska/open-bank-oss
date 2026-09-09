// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.IssueDisclosureSnapshotCommand
import com.openbank.document.application.port.out.DisclosureSnapshotRepository
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.DisclosureSnapshot
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.storage.ObjectStorePort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DisclosureSnapshotServiceTest {
    private val documents: DocumentRepositoryPort = mockk()
    private val snapshots: DisclosureSnapshotRepository = mockk()
    private val store: ObjectStorePort = mockk()
    private val service = DisclosureSnapshotService(documents, snapshots, store, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `signed owned PDF becomes an immutable content-addressed snapshot`(): Unit = runBlocking {
        val bytes = "%PDF-1.7 sealed".toByteArray()
        coEvery { snapshots.findByRequestId(REQUEST_ID) } returns null
        coEvery { documents.findById(SOURCE_ID) } returns source(bytes)
        coEvery { store.get("documents/$SOURCE_ID") } returns bytes
        coEvery { store.put(any(), any(), any(), any()) } returns Unit
        val captured = slot<DisclosureSnapshot>()
        coEvery { snapshots.createOrFind(capture(captured)) } answers { captured.captured }

        val result = service.issue(command())

        assertThat(result?.id).isEqualTo(SNAPSHOT_ID)
        assertThat(result?.sourceSha256).isEqualTo(Document.sha256(bytes))
        assertThat(result?.sha256).isEqualTo(Document.sha256(bytes))
        assertThat(result?.createdAt).isEqualTo(NOW)
        coVerify(exactly = 1) {
            store.put(
                "documents/disclosures/$SNAPSHOT_ID/${Document.sha256(bytes)}",
                bytes,
                "application/pdf",
                match { it["sha256"] == Document.sha256(bytes) },
            )
        }
    }

    @Test
    fun `request replay returns existing snapshot without reading live source`(): Unit = runBlocking {
        val bytes = "%PDF existing".toByteArray()
        val existing = snapshot(bytes)
        coEvery { snapshots.findByRequestId(REQUEST_ID) } returns existing
        coEvery { store.get(existing.storageKey) } returns bytes

        assertThat(service.issue(command())).isEqualTo(existing)

        coVerify(exactly = 0) { documents.findById(any()) }
        coVerify(exactly = 0) { store.put(any(), any(), any(), any()) }
    }

    @Test
    fun `request replay repairs a missing blob only from the still matching source`(): Unit = runBlocking {
        val bytes = "%PDF recoverable".toByteArray()
        val existing = snapshot(bytes)
        coEvery { snapshots.findByRequestId(REQUEST_ID) } returns existing
        coEvery { store.get(existing.storageKey) } throws NoSuchElementException("missing")
        coEvery { documents.findById(SOURCE_ID) } returns source(bytes)
        coEvery { store.get("documents/$SOURCE_ID") } returns bytes
        coEvery { store.put(existing.storageKey, bytes, "application/pdf", any()) } returns Unit
        coEvery { snapshots.createOrFind(any()) } returns existing

        assertThat(service.issue(command())).isEqualTo(existing)
        coVerify(exactly = 1) { store.put(existing.storageKey, bytes, "application/pdf", any()) }
    }

    @Test
    fun `foreign owner and unsigned source fail closed`(): Unit = runBlocking {
        coEvery { snapshots.findByRequestId(REQUEST_ID) } returns null
        coEvery { documents.findById(SOURCE_ID) } returns source("pdf".toByteArray()).copy(partyRef = "other")
        assertThatThrownBy { runBlocking { service.issue(command()) } }
            .isInstanceOf(IllegalArgumentException::class.java)

        coEvery { documents.findById(SOURCE_ID) } returns
            source("pdf".toByteArray()).copy(status = DocumentStatus.GENERATED)
        assertThatThrownBy { runBlocking { service.issue(command()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { store.put(any(), any(), any(), any()) }
    }

    @Test
    fun `tampered source bytes fail before snapshot write`(): Unit = runBlocking {
        val original = "%PDF original".toByteArray()
        coEvery { snapshots.findByRequestId(REQUEST_ID) } returns null
        coEvery { documents.findById(SOURCE_ID) } returns source(original)
        coEvery { store.get("documents/$SOURCE_ID") } returns "%PDF tampered".toByteArray()

        assertThatThrownBy { runBlocking { service.issue(command()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("digest")
        coVerify(exactly = 0) { store.put(any(), any(), any(), any()) }
    }

    @Test
    fun `concurrent request id collision cannot substitute another snapshot`(): Unit = runBlocking {
        val bytes = "%PDF expected".toByteArray()
        coEvery { snapshots.findByRequestId(REQUEST_ID) } returns null
        coEvery { documents.findById(SOURCE_ID) } returns source(bytes)
        coEvery { store.get("documents/$SOURCE_ID") } returns bytes
        coEvery { store.put(any(), any(), any(), any()) } returns Unit
        coEvery { snapshots.createOrFind(any()) } returns snapshot().copy(sourceDocumentId = UUID.randomUUID())

        assertThatThrownBy { runBlocking { service.issue(command()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("concurrently bound")
    }

    private fun command() = IssueDisclosureSnapshotCommand(REQUEST_ID, SOURCE_ID, PARTY)

    private fun source(bytes: ByteArray) = Document(
        SOURCE_ID,
        "SIGNED_AGREEMENT",
        "1",
        Document.sha256(bytes),
        "documents/$SOURCE_ID",
        "application/pdf",
        bytes.size.toLong(),
        DocumentStatus.SIGNED,
        emptyMap(),
        PARTY,
        null,
        null,
        null,
        NOW,
    )

    private fun snapshot(bytes: ByteArray = "%PDF snapshot".toByteArray()): DisclosureSnapshot {
        val digest = Document.sha256(bytes)
        return DisclosureSnapshot(
            SNAPSHOT_ID,
            REQUEST_ID,
            SOURCE_ID,
            PARTY,
            digest,
            digest,
            "documents/disclosures/$SNAPSHOT_ID/$digest",
            "application/pdf",
            bytes.size.toLong(),
            NOW,
        )
    }

    private companion object {
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000031")
        val SOURCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000032")
        val SNAPSHOT_ID: UUID = UUID.nameUUIDFromBytes("disclosure:$REQUEST_ID".toByteArray())
        const val PARTY = "00000000-0000-0000-0000-000000000033"
        val NOW: Instant = Instant.parse("2026-09-09T10:00:00Z")
    }
}
