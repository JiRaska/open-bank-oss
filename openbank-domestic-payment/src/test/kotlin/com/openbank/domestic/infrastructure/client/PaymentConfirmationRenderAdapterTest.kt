// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.PaymentConfirmationRenderException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Coverage for [PaymentConfirmationRenderAdapter]'s list-then-preview flow: document-service's
 * `preview` endpoint takes a raw `bodyHtml`, not a `templateCode`, so the adapter resolves the
 * current PUBLISHED template body first (ADR-0248 #3).
 */
class PaymentConfirmationRenderAdapterTest {

    private val client: DocumentServiceClient = mockk()

    private fun adapter(): PaymentConfirmationRenderAdapter =
        PaymentConfirmationRenderAdapter(client).also { it.self = it }

    @Test
    fun `resolves the PUBLISHED template body then merges data via preview`(): Unit = runBlocking {
        every { client.listTemplates(any()) } returns Uni.createFrom().item(
            listOf(
                DocumentTemplateSummary(code = "POTVRZENI_O_PLATBE_CS", status = "DRAFT", bodyHtml = "<old/>"),
                DocumentTemplateSummary(
                    code = "POTVRZENI_O_PLATBE_CS",
                    status = "PUBLISHED",
                    bodyHtml = "<b>{{document.status}}</b>",
                ),
                DocumentTemplateSummary(code = "OTHER_TEMPLATE", status = "PUBLISHED", bodyHtml = "<other/>"),
            ),
        )
        every { client.previewTemplate(any()) } returns
            Uni.createFrom().item(PreviewTemplateResponse(renderedHtml = "<b>SETTLED</b>"))

        val html = adapter().renderConfirmation(
            "POTVRZENI_O_PLATBE_CS",
            mapOf(
                "document" to mapOf("status" to "SETTLED"),
            ),
        )

        assertThat(html).isEqualTo("<b>SETTLED</b>")
        verify(exactly = 1) {
            client.previewTemplate(
                PreviewTemplateRequest(
                    bodyHtml = "<b>{{document.status}}</b>",
                    data = mapOf("document" to mapOf("status" to "SETTLED")),
                ),
            )
        }
    }

    @Test
    fun `throws PaymentConfirmationRenderException when no PUBLISHED template exists for the code`(): Unit =
        runBlocking {
            every { client.listTemplates(any()) } returns Uni.createFrom().item(emptyList())

            assertThatThrownBy {
                runBlocking { adapter().renderConfirmation("POTVRZENI_O_PLATBE_CS", emptyMap()) }
            }.isInstanceOf(PaymentConfirmationRenderException::class.java)
                .hasMessageContaining("POTVRZENI_O_PLATBE_CS")
        }

    @Test
    fun `wraps a downstream failure in PaymentConfirmationRenderException`(): Unit = runBlocking {
        every { client.listTemplates(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy {
            runBlocking { adapter().renderConfirmation("POTVRZENI_O_PLATBE_CS", emptyMap()) }
        }.isInstanceOf(PaymentConfirmationRenderException::class.java)
    }
}
