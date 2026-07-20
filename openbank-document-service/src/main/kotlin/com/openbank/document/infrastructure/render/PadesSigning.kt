// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Calendar
import java.util.Date

/**
 * A signing identity (private key + leaf cert + full chain) ready to produce a CMS signature.
 *
 * [ephemeral] marks a throwaway self-signed identity from [PadesSigning.generateEphemeralIdentity].
 * It is carried on the identity rather than left implicit at the call site because the *only* thing
 * separating a real seal from a legally worthless one is which identity produced it — and an
 * adapter that has fallen back has no other way to tell downstream. Callers MUST refuse to sign with
 * an ephemeral identity outside dev; see `PdfBoxPadesSealAdapter.sealPades`.
 */
data class SigningIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val certificateChain: List<X509Certificate>,
    val ephemeral: Boolean = false,
)

/**
 * Shared PAdES-B (`SubFilter: ETSI.CAdES.detached`) signing mechanics — Apache PDFBox's
 * external-signing API plus a Bouncy Castle detached CMS/PKCS7 signature — used by BOTH signature
 * layers a ceremony applies (ADR-0162 D4 continued):
 *  - [PdfBoxPadesSealAdapter]: the bank's institutional **electronic seal**, a stable long-lived
 *    organizational identity.
 *  - [OpenBaoClientSignatureAdapter]: each signer's **electronic signature**, a fresh one-time
 *    identity issued per signing act.
 *
 * The two differ only in WHICH [SigningIdentity] they supply and the signature's `Name`/`Reason`
 * metadata — the cryptographic mechanics (and PDF's native support for multiple, incremental
 * signatures layered onto the same document) are identical, so this is the one place that
 * mechanics is implemented.
 */
object PadesSigning {
    const val BC_PROVIDER = "BC"
    const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    const val RSA_KEY_SIZE = 2048
    private const val NOT_BEFORE_SKEW_MINUTES = 5L
    private const val EPHEMERAL_VALIDITY_DAYS = 365L

    // Visible signature block geometry (PDF points).
    private const val BLOCK_MARGIN = 48f
    private const val BLOCK_PADDING = 12f
    private const val TITLE_SIZE = 10f
    private const val LINE_SIZE = 8.5f
    private const val LINE_HEIGHT = 12f
    private const val BORDER_WIDTH = 0.7f
    private const val BORDER_GREY_RGB = 0x999999
    private const val TEXT_BLACK_RGB = 0x111111
    private const val TEXT_GREY_RGB = 0x555555
    private val BORDER_GREY = Color(BORDER_GREY_RGB)
    private val TEXT_BLACK = Color(TEXT_BLACK_RGB)
    private val TEXT_GREY = Color(TEXT_GREY_RGB)

    init {
        if (Security.getProvider(BC_PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * A throwaway, self-signed, in-memory-only identity — the DEV-ONLY fallback shared by every
     * PAdES adapter when it has no real key custody configured (a keystore file for the seal, a
     * reachable OpenBao for a client signature). Worthless as evidence: never issued by any
     * trusted CA, never persisted, gone on restart.
     */
    fun generateEphemeralIdentity(commonName: String, validityDays: Long = EPHEMERAL_VALIDITY_DAYS): SigningIdentity {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", BC_PROVIDER)
        keyPairGenerator.initialize(RSA_KEY_SIZE)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = Instant.now()
        val subject = X500Name("CN=$commonName, O=OpenBank")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(Duration.ofMinutes(NOT_BEFORE_SKEW_MINUTES))),
            Date.from(now.plus(Duration.ofDays(validityDays))),
            subject,
            keyPair.public,
        )
        val contentSigner = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider(BC_PROVIDER)
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(BC_PROVIDER)
            .getCertificate(certificateBuilder.build(contentSigner))

        return SigningIdentity(
            privateKey = keyPair.private,
            certificate = certificate,
            certificateChain = listOf(certificate),
            ephemeral = true,
        )
    }

    /**
     * True if [pdf] already carries a PAdES signature whose `Name` is [name]. Lets a signing act be
     * idempotent: a retry after a persistence failure must not layer a second identical signature for
     * a signer (or the seal) that already signed this document (ADR-0162 D7 robustness).
     */
    fun hasSignatureNamed(pdf: ByteArray, name: String): Boolean =
        Loader.loadPDF(pdf).use { document -> document.signatureDictionaries.any { it.name == name } }

    /**
     * Draws a human-readable signature block onto the last page and returns the new bytes.
     *
     * The cryptographic signature lives in the PDF's signature dictionary, which only a validator
     * (Adobe's signature panel) surfaces — on the page itself it is invisible, so a customer opening
     * their signed contract in any ordinary viewer sees an unsigned-looking document. This block is
     * that missing visual evidence.
     *
     * Deliberately stamped BEFORE any signature is applied, as ordinary page content rather than a
     * signature widget's appearance stream: the block is then part of the byte range both the
     * client signature and the institutional seal cover, so it cannot be altered or stripped without
     * breaking them. Stamping afterwards would leave the visible text outside the signed bytes —
     * exactly the discrepancy a signature is supposed to make impossible.
     *
     * No-op if [lines] is empty. Callers must not stamp an already-signed PDF (it would invalidate
     * the existing signature); [hasSignatureNamed] is the guard.
     */
    fun stampSignatureBlock(pdf: ByteArray, title: String, lines: List<String>): ByteArray {
        if (lines.isEmpty()) return pdf
        return Loader.loadPDF(pdf).use { document ->
            val page = document.getPage(document.numberOfPages - 1)
            val box = page.mediaBox
            val blockHeight = BLOCK_PADDING * 2 + TITLE_SIZE + LINE_HEIGHT * lines.size
            val top = box.lowerLeftY + BLOCK_MARGIN + blockHeight
            val left = box.lowerLeftX + BLOCK_MARGIN
            val width = box.width - BLOCK_MARGIN * 2

            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                content.setLineWidth(BORDER_WIDTH)
                content.setStrokingColor(BORDER_GREY)
                content.addRect(left, box.lowerLeftY + BLOCK_MARGIN, width, blockHeight)
                content.stroke()

                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), TITLE_SIZE)
                content.setNonStrokingColor(TEXT_BLACK)
                content.newLineAtOffset(left + BLOCK_PADDING, top - BLOCK_PADDING - TITLE_SIZE)
                content.showText(title)
                content.endText()

                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), LINE_SIZE)
                content.setNonStrokingColor(TEXT_GREY)
                content.newLineAtOffset(left + BLOCK_PADDING, top - BLOCK_PADDING - TITLE_SIZE - LINE_HEIGHT)
                lines.forEach { line ->
                    content.showText(line)
                    content.newLineAtOffset(0f, -LINE_HEIGHT)
                }
                content.endText()
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            output.toByteArray()
        }
    }

    /** Appends one more PAdES signature (an incremental PDF update) using [identity]. */
    fun applySignature(pdf: ByteArray, identity: SigningIdentity, name: String, reason: String): ByteArray =
        Loader.loadPDF(pdf).use { document ->
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                setName(name)
                setReason(reason)
                setSignDate(Calendar.getInstance())
            }
            val signatureOptions = SignatureOptions()
            try {
                signatureOptions.preferredSignatureSize = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2
                document.addSignature(signature, Pkcs7SignatureInterface(identity), signatureOptions)
                val output = ByteArrayOutputStream()
                document.saveIncremental(output)
                output.toByteArray()
            } finally {
                signatureOptions.close()
            }
        }

    /**
     * PDFBox's external-signing callback: given the exact byte range PDFBox has carved out around
     * the signature placeholder (the PDF `ByteRange`), produce the detached CMS/PKCS7 signature
     * bytes to embed. The content is read fully into memory — acceptable for the document sizes
     * this service handles (statements/contracts, not multi-GB files).
     */
    private class Pkcs7SignatureInterface(private val identity: SigningIdentity) : SignatureInterface {
        override fun sign(content: InputStream): ByteArray {
            val digestCalculatorProvider = JcaDigestCalculatorProviderBuilder()
                .setProvider(BC_PROVIDER)
                .build()
            val contentSigner = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BC_PROVIDER)
                .build(identity.privateKey)
            val signerInfoGenerator = JcaSignerInfoGeneratorBuilder(digestCalculatorProvider)
                .build(contentSigner, identity.certificate)

            val generator = CMSSignedDataGenerator()
            generator.addSignerInfoGenerator(signerInfoGenerator)
            generator.addCertificates(JcaCertStore(identity.certificateChain))

            val signedData = generator.generate(CMSProcessableByteArray(content.readBytes()), false)
            return signedData.encoded
        }
    }
}
