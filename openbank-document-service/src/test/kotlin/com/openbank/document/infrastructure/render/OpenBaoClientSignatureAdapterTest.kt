// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * No real OpenBao is available in this test (or in local dev) — a nonexistent ServiceAccount
 * token path is exactly the signal [OpenBaoClientSignatureAdapter] uses to take its DEV-ONLY
 * ephemeral fallback, so this test exercises that path (the real-OpenBao path is verified live in
 * the sandbox, ADR-0162 D4 continued / runbook 0008).
 */
class OpenBaoClientSignatureAdapterTest {

    private fun adapter(requireTrustedIssuer: Boolean = false) = OpenBaoClientSignatureAdapter(
        baoAddr = "http://openbao.invalid:8200",
        role = "document-service-signing",
        issuePath = "pki-document-signing/issue/client-signing",
        saTokenPath = "/nonexistent/path/token",
        ttl = "300s",
        requireTrustedIssuer = requireTrustedIssuer,
        objectMapper = ObjectMapper(),
    )

    @Test
    fun `falls back to a valid ephemeral one-time signature when OpenBao is not reachable`(): Unit = runBlocking {
        val pdf = blankPdf()

        val signed = adapter().signAsClient(pdf, "party-42")

        val signatures = Loader.loadPDF(signed).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(1)
        assertThat(signatures[0].name).isEqualTo("party-42")
    }

    @Test
    fun `fails loud instead of falling back when require-trusted-issuer is set`(): Unit = runBlocking {
        // Fail-closed go-live gate: with no real OpenBao reachable and the guard on, a signing act
        // must refuse rather than silently produce an evidence-worthless ephemeral signature.
        assertThatThrownBy { runBlocking { adapter(requireTrustedIssuer = true).signAsClient(blankPdf(), "party-42") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("require-trusted-issuer")
    }

    private fun blankPdf(): ByteArray {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        }
    }
}
