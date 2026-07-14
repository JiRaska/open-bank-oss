// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * A configured-but-nonexistent keystore path must fall back to the ephemeral dev identity, not
 * crash boot — this is what makes the gitops rollout order (wiring the volume/env-var before the
 * OpenBao KV secret is actually populated) safe (ADR-0162 D4 continued).
 */
class PdfBoxPadesSealAdapterTest {

    @Test
    fun `a nonexistent keystore path falls back to the ephemeral identity instead of throwing`(): Unit = runBlocking {
        val adapter = PdfBoxPadesSealAdapter(
            keystorePath = Optional.of("/nonexistent/path/keystore.p12"),
            keystorePassword = Optional.of("irrelevant"),
        )

        val signed = adapter.sealPades(blankPdf(), ceremony())

        val signatures = Loader.loadPDF(signed).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(1)
        assertThat(signatures[0].name).isEqualTo("OpenBank")
    }

    @Test
    fun `an absent keystore path also falls back to the ephemeral identity`(): Unit = runBlocking {
        val adapter = PdfBoxPadesSealAdapter(keystorePath = Optional.empty(), keystorePassword = Optional.empty())

        val signed = adapter.sealPades(blankPdf(), ceremony())

        val signatures = Loader.loadPDF(signed).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(1)
    }

    private fun ceremony() = SignatureCeremony(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        signers = emptyList(),
        status = CeremonyStatus.COMPLETED,
        signatureLevel = SignatureLevel.ADVANCED,
        createdAt = Instant.parse("2026-01-15T10:15:30Z"),
    )

    private fun blankPdf(): ByteArray {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        }
    }
}
