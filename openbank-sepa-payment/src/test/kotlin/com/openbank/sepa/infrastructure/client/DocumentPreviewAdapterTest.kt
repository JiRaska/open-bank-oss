// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.DocumentTemplateUnavailableException
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DocumentPreviewAdapterTest {

    private val client = mockk<DocumentServiceClient>()
    private val adapter = DocumentPreviewAdapter(client).also { it.self = it }

    private fun template(code: String, status: String, bodyHtml: String = "<p>{{document.status}}</p>") =
        DocumentTemplateClientResponse(code = code, bodyHtml = bodyHtml, locale = "en", status = status)

    @Test
    fun `resolves the PUBLISHED template by code and previews it`() {
        every { client.listTemplates(any()) } returns Uni.createFrom().item(
            listOf(
                template("POTVRZENI_O_PLATBE_EN", "DRAFT", bodyHtml = "<p>draft, must be ignored</p>"),
                template("POTVRZENI_O_PLATBE_EN", "PUBLISHED", bodyHtml = "<p>{{document.status}}</p>"),
                template("OTHER_TEMPLATE", "PUBLISHED"),
            ),
        )
        every { client.preview(any()) } returns
            Uni.createFrom().item(PreviewTemplateClientResponse("<p>COMPLETED</p>"))

        val rendered = runBlocking {
            adapter.renderTemplate("POTVRZENI_O_PLATBE_EN", mapOf("document" to mapOf("status" to "COMPLETED")))
        }

        assertThat(rendered).isEqualTo("<p>COMPLETED</p>")
    }

    @Test
    fun `no PUBLISHED template for the code fails closed`() {
        every { client.listTemplates(any()) } returns Uni.createFrom().item(
            listOf(template("POTVRZENI_O_PLATBE_EN", "DRAFT")),
        )

        assertThatThrownBy { runBlocking { adapter.renderTemplate("POTVRZENI_O_PLATBE_EN", emptyMap()) } }
            .isInstanceOf(DocumentTemplateUnavailableException::class.java)
            .hasMessageContaining("POTVRZENI_O_PLATBE_EN")
    }

    @Test
    fun `a document-service fault listing templates fails closed, never silently succeeds`() {
        every { client.listTemplates(any()) } returns Uni.createFrom().failure(RuntimeException("boom"))

        assertThatThrownBy { runBlocking { adapter.renderTemplate("POTVRZENI_O_PLATBE_EN", emptyMap()) } }
            .isInstanceOf(DocumentTemplateUnavailableException::class.java)
    }

    @Test
    fun `a document-service fault rendering the preview fails closed`() {
        every { client.listTemplates(any()) } returns Uni.createFrom().item(
            listOf(template("POTVRZENI_O_PLATBE_EN", "PUBLISHED")),
        )
        every { client.preview(any()) } returns Uni.createFrom().failure(RuntimeException("boom"))

        assertThatThrownBy { runBlocking { adapter.renderTemplate("POTVRZENI_O_PLATBE_EN", emptyMap()) } }
            .isInstanceOf(DocumentTemplateUnavailableException::class.java)
    }
}
