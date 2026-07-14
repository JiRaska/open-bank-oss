// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.util.Selector
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Real cryptographic coverage for the mechanics both signature layers share (ADR-0162 D4
 * continued): a signature applied via [PadesSigning.applySignature] must actually verify against
 * its own embedded certificate, and PDF's native multi-signature support must genuinely hold —
 * the client's one-time signature and the bank's seal are two independent, layered signatures over
 * the same evolving document, not a single overwritten one.
 */
class PadesSigningTest {

    @Test
    fun `a single applied signature verifies against its own certificate`() {
        val pdf = blankPdf()
        val identity = PadesSigning.generateEphemeralIdentity("Test Signer")

        val signed = PadesSigning.applySignature(pdf, identity, "Test Signer", "unit test signature")

        val signatures = Loader.loadPDF(signed).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(1)
        assertThat(signatures[0].name).isEqualTo("Test Signer")
        assertThat(signatures[0].reason).isEqualTo("unit test signature")
        assertThat(verifies(signatures[0].getContents(signed), signatures[0].getSignedContent(signed))).isTrue()
    }

    @Test
    fun `two signatures layered on the same document both verify independently`() {
        val pdf = blankPdf()
        val clientIdentity = PadesSigning.generateEphemeralIdentity("party-42")
        val bankIdentity = PadesSigning.generateEphemeralIdentity("OpenBank")

        val clientSigned = PadesSigning.applySignature(pdf, clientIdentity, "party-42", "client signature")
        val fullySigned = PadesSigning.applySignature(clientSigned, bankIdentity, "OpenBank", "institutional seal")

        val signatures = Loader.loadPDF(fullySigned).use { it.signatureDictionaries }
        assertThat(signatures).hasSize(2)
        assertThat(signatures.map { it.name }).containsExactly("party-42", "OpenBank")
        signatures.forEach { sig ->
            assertThat(verifies(sig.getContents(fullySigned), sig.getSignedContent(fullySigned))).isTrue()
        }
    }

    @Test
    fun `hasSignatureNamed detects a present signature and ignores absent names and unsigned pdfs`() {
        val pdf = blankPdf()
        val signed = PadesSigning.applySignature(pdf, PadesSigning.generateEphemeralIdentity("party-1"), "party-1", "s")

        assertThat(PadesSigning.hasSignatureNamed(signed, "party-1")).isTrue()
        assertThat(PadesSigning.hasSignatureNamed(signed, "party-2")).isFalse()
        assertThat(PadesSigning.hasSignatureNamed(pdf, "party-1")).isFalse()
    }

    /** Verifies a detached CMS/PKCS7 signature against the certificate embedded in it. */
    @Suppress("UNCHECKED_CAST")
    private fun verifies(cmsBytes: ByteArray, signedContent: ByteArray): Boolean {
        val signedData = CMSSignedData(CMSProcessableByteArray(signedContent), cmsBytes)
        val signerInfo = signedData.signerInfos.signers.first()
        val selector = signerInfo.sid as Selector<X509CertificateHolder>
        val certHolder = signedData.certificates.getMatches(selector).first() as X509CertificateHolder
        return signerInfo.verify(JcaSimpleSignerInfoVerifierBuilder().build(certHolder))
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
