// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.DocumentTemplateUseCase
import com.openbank.document.application.port.`in`.ExportExternalDisclosureCommand
import com.openbank.document.application.port.`in`.ExternalDisclosureExportUseCase
import com.openbank.document.application.port.`in`.OnboardingDocumentUseCase
import com.openbank.document.application.port.`in`.SealedExternalDisclosure
import com.openbank.document.infrastructure.rest.dto.ExportExternalDisclosureRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.BadRequestException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExternalDisclosureExportResourceTest {

    private val exporter = mockk<ExternalDisclosureExportUseCase>()
    private val resource = DocumentResource(
        templateUseCase = mockk<DocumentTemplateUseCase>(),
        renderUseCase = mockk<DocumentRenderUseCase>(),
        queryUseCase = mockk<DocumentQueryUseCase>(),
        onboardingUseCase = mockk<OnboardingDocumentUseCase>(),
        externalDisclosureExportUseCase = exporter,
    )

    @Test
    fun `exports only the sealed PDF returned by the export use case`(): Unit = runBlocking {
        val documentId = UUID.randomUUID()
        val disclosureId = UUID.randomUUID()
        val issuedAt = Instant.parse("2026-09-08T18:00:00Z")
        val sealedBytes = byteArrayOf(1, 2, 3)
        val command = ExportExternalDisclosureCommand(documentId, disclosureId, "Jana N.", issuedAt)
        coEvery { exporter.export(command) } returns
            SealedExternalDisclosure(documentId, disclosureId, "application/pdf", sealedBytes)

        val response = resource.exportExternalDisclosure(
            documentId,
            ExportExternalDisclosureRequest(disclosureId, "Jana N.", issuedAt),
        )

        assertThat(response.status).isEqualTo(200)
        assertThat(response.mediaType.toString()).isEqualTo("application/pdf")
        assertThat(response.entity).isSameAs(sealedBytes)
        coVerify(exactly = 1) { exporter.export(command) }
    }

    @Test
    fun `rejects blank recipient before any export work`(): Unit = runBlocking {
        val error = try {
            resource.exportExternalDisclosure(
                UUID.randomUUID(),
                ExportExternalDisclosureRequest(UUID.randomUUID(), "  ", Instant.now()),
            )
            null
        } catch (exception: BadRequestException) {
            exception
        }

        assertThat(error).isInstanceOf(BadRequestException::class.java)
        coVerify(exactly = 0) { exporter.export(any()) }
    }
}
