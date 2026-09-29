// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.ExportExternalDisclosureCommand
import com.openbank.document.application.port.out.DisclosureWatermarkPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.ExternalDisclosureSealPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.storage.ObjectStorePort
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExternalDisclosureExportServiceTest {
    private val documentId = UUID.randomUUID()
    private val disclosureId = UUID.randomUUID()

    @Test
    fun `returns only a watermarked then institutionally sealed derivative`(): Unit = runBlocking {
        val repository = mockk<DocumentRepositoryPort>()
        val store = mockk<ObjectStorePort>()
        val watermark = mockk<DisclosureWatermarkPort>()
        val seal = mockk<ExternalDisclosureSealPort>()
        val source = "original".toByteArray()
        val marked = "marked".toByteArray()
        val sealed = "sealed".toByteArray()
        coEvery { repository.findById(documentId) } returns document("application/pdf")
        coEvery { store.get("document/source") } returns source
        coEvery { watermark.watermark(source, "External accountant", disclosureId) } returns marked
        coEvery { seal.sealExternalDisclosure(marked, disclosureId) } returns sealed
        val service = ExternalDisclosureExportService(repository, store, watermark, seal)

        val result = service.export(command())

        assertThat(result.bytes).isEqualTo(sealed)
        assertThat(result.bytes).isNotEqualTo(source)
        coVerifyOrder {
            store.get("document/source")
            watermark.watermark(source, "External accountant", disclosureId)
            seal.sealExternalDisclosure(marked, disclosureId)
        }
    }

    @Test
    fun `refuses a non PDF before reading source bytes`(): Unit = runBlocking {
        val repository = mockk<DocumentRepositoryPort>()
        val store = mockk<ObjectStorePort>()
        val watermark = mockk<DisclosureWatermarkPort>()
        val seal = mockk<ExternalDisclosureSealPort>()
        coEvery { repository.findById(documentId) } returns document("text/plain")
        val service = ExternalDisclosureExportService(repository, store, watermark, seal)

        assertThatThrownBy { runBlocking { service.export(command()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sealed PDF")
        io.mockk.coVerify(exactly = 0) { store.get(any()) }
    }

    private fun command() = ExportExternalDisclosureCommand(
        documentId = documentId,
        disclosureId = disclosureId,
        recipientLabel = "External accountant",
        issuedAt = Instant.parse("2026-09-08T12:00:00Z"),
    )

    private fun document(contentType: String) = Document(
        id = documentId,
        templateCode = "STATEMENT",
        templateVersion = "1",
        sha256 = "a".repeat(64),
        storageKey = "document/source",
        contentType = contentType,
        sizeBytes = 8,
        status = DocumentStatus.SIGNED,
        metadata = emptyMap(),
        partyRef = null,
        caseRef = null,
        productRef = null,
        retainUntil = null,
        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
    )
}
