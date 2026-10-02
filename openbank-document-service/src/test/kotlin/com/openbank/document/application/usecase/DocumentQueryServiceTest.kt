// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.storage.ObjectStorePort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Instant
import java.util.UUID

/**
 * The interesting behaviour here is [DocumentQueryService.getContent]'s mapping of the TWO
 * backend-specific "not found" signals to `null`, and — the half that actually matters
 * operationally — that a real I/O failure is NOT swallowed into a 404.
 */
class DocumentQueryServiceTest {

    private val repo = mockk<DocumentRepositoryPort>()
    private val objectStore = mockk<ObjectStorePort>()
    private val service = DocumentQueryService(repo, objectStore)

    private val id = UUID.randomUUID()
    private val document = Document(
        id = id,
        templateCode = "VOP",
        templateVersion = "1.0.0",
        sha256 = "b".repeat(64),
        storageKey = "document/rendered/$id",
        contentType = "application/pdf",
        sizeBytes = 12,
        status = DocumentStatus.GENERATED,
        metadata = emptyMap(),
        partyRef = "party-1",
        caseRef = null,
        productRef = null,
        retainUntil = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `getContent returns null without touching the object store when the row is unknown`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns null

        assertThat(service.getContent(id)).isNull()
        coVerify(exactly = 0) { objectStore.get(any()) }
    }

    @Test
    fun `getContent reads the bytes under the row's own storage key`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns document
        coEvery { objectStore.get(document.storageKey) } returns byteArrayOf(1, 2, 3)

        assertThat(service.getContent(id)).containsExactly(1, 2, 3)
    }

    @Test
    fun `a Postgres-adapter NoSuchElementException maps to null, not an error`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns document
        coEvery { objectStore.get(any()) } throws NoSuchElementException("no row")

        assertThat(service.getContent(id)).isNull()
    }

    @Test
    fun `an S3-adapter NoSuchKeyException maps to null, not an error`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns document
        coEvery { objectStore.get(any()) } throws NoSuchKeyException.builder().message("missing").build()

        assertThat(service.getContent(id)).isNull()
    }

    @Test
    fun `a genuine store failure propagates instead of masquerading as a missing document`() {
        coEvery { repo.findById(id) } returns document
        coEvery { objectStore.get(any()) } throws IllegalStateException("credentials expired")

        assertThatThrownBy { runBlocking { service.getContent(id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("credentials expired")
    }

    @Test
    fun `paging arguments are passed through to the repository verbatim`(): Unit = runBlocking {
        coEvery { repo.findByPartyPaged("party-1", 2, 25) } returns listOf(document)
        coEvery { repo.countByParty("party-1") } returns 51L

        assertThat(service.listByPartyPaged("party-1", 2, 25)).containsExactly(document)
        assertThat(service.countByParty("party-1")).isEqualTo(51L)
    }
}
