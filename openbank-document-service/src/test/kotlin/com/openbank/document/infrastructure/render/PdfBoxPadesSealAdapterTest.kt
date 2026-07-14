// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The seal adapter's DEV-ONLY ephemeral fallback (no keystore configured) versus its fail-closed
 * go-live gate (ADR-0162 D4 continued): with [requireTrustedIssuer] set, the bean must refuse to
 * construct rather than boot the service on an evidence-worthless in-memory seal identity.
 */
class PdfBoxPadesSealAdapterTest {

    @Test
    fun `with no keystore and the guard off, applies a DEV-ONLY ephemeral seal`(): Unit = runBlocking {
        val adapter = PdfBoxPadesSealAdapter(
            keystorePath = Optional.empty(),
            keystorePassword = Optional.empty(),
            requireTrustedIssuer = false,
        )

        val sealed = adapter.sealPades(blankPdf(), ceremony())

        val signatures = Loader.loadPDF(sealed).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(1)
        assertThat(signatures[0].name).isEqualTo("OpenBank")
    }

    @Test
    fun `with no keystore and the guard on, refuses to start (fail-closed)`() {
        assertThatThrownBy {
            PdfBoxPadesSealAdapter(
                keystorePath = Optional.empty(),
                keystorePassword = Optional.empty(),
                requireTrustedIssuer = true,
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("require-trusted-issuer")
    }

    private fun ceremony() = SignatureCeremony(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        signers = listOf(
            Signer(partyRef = "party-1", order = 1, status = SignerStatus.SIGNED, signedAt = Instant.EPOCH),
        ),
        status = CeremonyStatus.COMPLETED,
        signatureLevel = SignatureLevel.ADVANCED,
        createdAt = Instant.EPOCH,
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
