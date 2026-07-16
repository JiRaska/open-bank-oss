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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Two gates, deliberately at different points in the lifecycle (ADR-0162 D4, ADR-0172 D2):
 *
 *  - **Boot** — a configured-but-nonexistent keystore path falls back to the ephemeral dev identity
 *    rather than crashing. This is what makes the gitops rollout order (wiring the volume/env-var
 *    before the OpenBao KV secret is populated) safe, and it must stay that way.
 *  - **Seal** — but SIGNING with that ephemeral identity fails closed unless explicitly allowed.
 *    Booting on a throwaway cert is survivable; sealing with one produces legally worthless evidence
 *    that looks exactly like success, which is the failure mode worth preventing.
 */
class PdfBoxPadesSealAdapterTest {

    // ---- boot: fallback must not crash (the rollout-order guarantee) -----------------------

    @Test
    fun `a nonexistent keystore path falls back to the ephemeral identity instead of throwing`(): Unit = runBlocking {
        val adapter = adapter(keystorePath = Optional.of("/nonexistent/path/keystore.p12"))

        val signed = adapter.sealPades(blankPdf(), ceremony())

        val signatures = Loader.loadPDF(signed).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(1)
        assertThat(signatures[0].name).isEqualTo("OpenBank")
    }

    @Test
    fun `an absent keystore path also falls back to the ephemeral identity`(): Unit = runBlocking {
        val adapter = adapter(keystorePath = Optional.empty())

        val signed = adapter.sealPades(blankPdf(), ceremony())

        assertThat(Loader.loadPDF(signed).use { it.signatureDictionaries }).hasSize(1)
    }

    @Test
    fun `with no usable keystore and the boot guard on, refuses to start (fail-closed)`() {
        assertThatThrownBy {
            adapter(keystorePath = Optional.empty(), requireTrustedIssuer = true)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("require-trusted-issuer")
    }

    // ---- seal: the gate that protects evidence (ADR-0172 D2) -------------------------------

    @Test
    fun `sealing with an ephemeral identity fails closed by default`(): Unit = runBlocking {
        // The real-world shape of this: gitops sets the keystore path, the OpenBao KV was never
        // seeded, the volume mounts empty. The service boots (above), then must NOT seal.
        val adapter = adapter(
            keystorePath = Optional.of("/nonexistent/path/keystore.p12"),
            allowEphemeralSeals = false,
        )

        assertThatThrownBy { runBlocking { adapter.sealPades(blankPdf(), ceremony()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("worthless as evidence")
    }

    @Test
    fun `the failure names the ceremony and the fix, not just the symptom`(): Unit = runBlocking {
        val c = ceremony()
        val adapter = adapter(keystorePath = Optional.empty(), allowEphemeralSeals = false)

        assertThatThrownBy { runBlocking { adapter.sealPades(blankPdf(), c) } }
            // An operator reading this at 3am needs the ceremony to correlate and the runbook to act.
            .hasMessageContaining(c.id.toString())
            .hasMessageContaining("0008-openbao-document-signing-pki")
    }

    @Test
    fun `it fails before touching the PDF — no half-sealed output`(): Unit = runBlocking {
        val pdf = blankPdf()
        val adapter = adapter(keystorePath = Optional.empty(), allowEphemeralSeals = false)

        runCatching { adapter.sealPades(pdf, ceremony()) }

        // The guard is the first statement in sealPades: a refused seal must leave nothing behind
        // that a retry or a caller could mistake for a signed document.
        assertThat(Loader.loadPDF(pdf).use { it.signatureDictionaries }).isEmpty()
    }

    @Test
    fun `a real keystore seals regardless of the ephemeral flag`(): Unit = runBlocking {
        // The gate keys on the IDENTITY being ephemeral, not on the flag alone — a provisioned
        // keystore must never be blocked by a fail-closed default.
        val keystore = writeThrowawayKeystore()
        val adapter = adapter(
            keystorePath = Optional.of(keystore.toString()),
            keystorePassword = Optional.of(KEYSTORE_PASSWORD),
            allowEphemeralSeals = false,
        )

        val signed = adapter.sealPades(blankPdf(), ceremony())

        assertThat(Loader.loadPDF(signed).use { it.signatureDictionaries }).hasSize(1)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun adapter(
        keystorePath: Optional<String>,
        keystorePassword: Optional<String> = Optional.of("irrelevant"),
        requireTrustedIssuer: Boolean = false,
        // Default true so the boot-fallback tests above keep asserting what they were written for;
        // the seal-gate tests opt out explicitly.
        allowEphemeralSeals: Boolean = true,
    ) = PdfBoxPadesSealAdapter(
        keystorePath = keystorePath,
        keystorePassword = keystorePassword,
        requireTrustedIssuer = requireTrustedIssuer,
        allowEphemeralSeals = allowEphemeralSeals,
    )

    /**
     * A PKCS12 on disk holding a non-ephemeral identity. Cryptographically this is still
     * self-signed — the point is only that it arrives via `loadFromKeystore`, which is what marks it
     * as not-ephemeral, exactly as a real organizational keystore would.
     */
    private fun writeThrowawayKeystore(): java.nio.file.Path {
        val identity = PadesSigning.generateEphemeralIdentity("Test Org Keystore")
        val keyStore = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "seal",
                identity.privateKey,
                KEYSTORE_PASSWORD.toCharArray(),
                identity.certificateChain.toTypedArray(),
            )
        }
        val path = java.nio.file.Files.createTempFile("seal-keystore", ".p12")
        java.nio.file.Files.newOutputStream(path).use { keyStore.store(it, KEYSTORE_PASSWORD.toCharArray()) }
        path.toFile().deleteOnExit()
        return path
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

    private companion object {
        const val KEYSTORE_PASSWORD = "test-password"
    }
}
