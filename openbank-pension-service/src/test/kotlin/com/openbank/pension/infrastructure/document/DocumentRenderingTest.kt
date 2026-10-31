// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.document

import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DocumentRenderingTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val sent = mutableListOf<RenderDocumentRequestDto>()

    private fun rendering(answer: () -> RenderedDocumentDto) = DocumentRendering { req ->
        sent += req
        answer()
    }

    private suspend fun DocumentRendering.kid(language: String?) = render(
        "pension-dps-key-information",
        language,
        mapOf("strategyCode" to "BALANCED"),
        "party",
        "case",
        "pension-dps",
    )

    @Test
    fun `the hash returned is document-service's hash of the stored document, verbatim`(): Unit = runBlocking {
        val doc = rendering { RenderedDocumentDto(id = "d-1", sha256 = sha.uppercase()) }.kid("cs")
        assertThat(doc.documentId).isEqualTo("d-1")
        // The SCA challenge is bound to exactly this value (OnboardingService) — never recomputed here.
        assertThat(doc.sha256).isEqualTo(sha)
    }

    @Test
    fun `the language selects the template, cs is the fallback, and every render is marked for legal review`(): Unit =
        runBlocking {
            val r = rendering { RenderedDocumentDto(id = "d", sha256 = sha) }
            r.kid("en-GB")
            r.kid(null)
            r.kid("de")
            assertThat(sent.map { it.templateCode }).containsExactly(
                "pension-dps-key-information-en",
                "pension-dps-key-information-cs",
                "pension-dps-key-information-cs",
            )
            assertThat(sent).allSatisfy { assertThat(it.data).containsEntry(DocumentRendering.LEGAL_REVIEW_KEY, true) }
            assertThat(sent.first().partyRef).isEqualTo("party")
        }

    @Test
    fun `an answer without a well-formed hash or an id is refused, never trusted`() {
        listOf(
            RenderedDocumentDto(id = "d", sha256 = null),
            RenderedDocumentDto(id = "d", sha256 = "abc"),
            RenderedDocumentDto(id = "d", sha256 = "z".repeat(64)),
            RenderedDocumentDto(id = " ", sha256 = sha),
            RenderedDocumentDto(id = null, sha256 = sha),
        ).forEach { answer ->
            assertThatThrownBy { runBlocking { rendering { answer }.kid("cs") } }
                .isInstanceOf(IntegrationUnavailableException::class.java)
        }
    }

    @Test
    fun `document-service refusing (missing template, 4xx, 5xx) fails closed`() {
        assertThatThrownBy { runBlocking { rendering { throw WebApplicationException(404) }.kid("cs") } }
            .isInstanceOf(IntegrationUnavailableException::class.java)
            .hasMessageContaining("pension-dps-key-information-cs")
    }
}
