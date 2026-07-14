// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.application.port.out.SignatureSealPort
import com.openbank.document.domain.model.SignatureCeremony
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Optional

/**
 * Phase-1 [SignatureSealPort] adapter (ADR-0162 D4): applies a server-side **PAdES-B** seal —
 * `SubFilter: ETSI.CAdES.detached` (`PDSignature.SUBFILTER_ETSI_CADES_DETACHED`), the standard
 * PAdES-Basic subfilter — using Apache PDFBox's external-signing API and a Bouncy Castle detached
 * CMS/PKCS7 signature over the PDF's signed byte range.
 *
 * This is legally an *advanced* electronic signature (AdES), combined with the SCA-bound evidence
 * captured by [com.openbank.document.application.port.out.SignerVerificationPort] and the
 * platform's audit hash-chain (ADR-0086/ADR-0133) for non-repudiation.
 *
 * **Phase 2 is a genuinely different thing — do not conflate the two.** Phase 2 (QES/QSeal,
 * ADR-0007, ADR-0162 D4) is EU DSS (the EU-canonical eIDAS reference implementation, LGPL-2.1 —
 * requires legal sign-off before it can be added to this codebase) producing PAdES-LTA, keyed by
 * QSeal/HSM custody rather than a certificate loaded from a keystore file or generated in-process.
 * This adapter is Apache-2.0 (PDFBox) + the permissive Bouncy Castle License (bcprov/bcpkix) —
 * neither requires that sign-off — and its key custody model (file-based PKCS12, or an ephemeral
 * in-memory cert) is explicitly *not* HSM-backed.
 */
@ApplicationScoped
class PdfBoxPadesSealAdapter(
    @ConfigProperty(name = "openbank.signature.keystore-path")
    keystorePath: Optional<String>,

    @ConfigProperty(name = "openbank.signature.keystore-password")
    keystorePassword: Optional<String>,
) : SignatureSealPort {

    private val logger = Logger.getLogger(PdfBoxPadesSealAdapter::class.java)

    private val identity: SigningIdentity

    init {
        Security.addProvider(BouncyCastleProvider())
        identity = if (keystorePath.isPresent) {
            loadFromKeystore(keystorePath.get(), keystorePassword.orElse(""))
        } else {
            logger.warn(
                "openbank.signature.keystore-path is not configured — PdfBoxPadesSealAdapter is " +
                    "generating an EPHEMERAL, in-memory, non-persisted self-signed X.509 certificate " +
                    "purely so the service is runnable out of the box. This is DEV-ONLY: every PAdES " +
                    "seal applied while this warning fires is worthless as evidence (the private key " +
                    "vanishes on restart and was never issued by any trusted CA). Production MUST " +
                    "configure openbank.signature.keystore-path / openbank.signature.keystore-password " +
                    "with a real organizational PKCS12 certificate before this service handles a real " +
                    "signature ceremony.",
            )
            generateEphemeralIdentity()
        }
    }

    override suspend fun sealPades(pdf: ByteArray, ceremony: SignatureCeremony): ByteArray =
        withContext(Dispatchers.IO) {
            Loader.loadPDF(pdf).use { document ->
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                    setName(ORGANIZATION_NAME)
                    setReason(SEAL_REASON)
                    setSignDate(Calendar.getInstance())
                }
                val signatureOptions = SignatureOptions()
                try {
                    signatureOptions.preferredSignatureSize = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2
                    document.addSignature(signature, Pkcs7SignatureInterface(), signatureOptions)
                    val output = ByteArrayOutputStream()
                    document.saveIncremental(output)
                    output.toByteArray()
                } finally {
                    signatureOptions.close()
                }
            }
        }

    /**
     * PDFBox's external-signing callback: given the exact byte range PDFBox has carved out around
     * the signature placeholder (the PDF `ByteRange`), produce the detached CMS/PKCS7 signature
     * bytes to embed. The content is read fully into memory — acceptable for the document sizes
     * this service handles (statements/contracts, not multi-GB files).
     */
    private inner class Pkcs7SignatureInterface : SignatureInterface {
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

    private data class SigningIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val certificateChain: List<X509Certificate>,
    )

    private fun loadFromKeystore(path: String, password: String): SigningIdentity {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(path).use { keyStore.load(it, password.toCharArray()) }
        val alias = keyStore.aliases().asSequence().first { keyStore.isKeyEntry(it) }
        val privateKey = keyStore.getKey(alias, password.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        val chain = keyStore.getCertificateChain(alias).map { it as X509Certificate }
        return SigningIdentity(privateKey, certificate, chain)
    }

    private fun generateEphemeralIdentity(): SigningIdentity {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", BC_PROVIDER)
        keyPairGenerator.initialize(RSA_KEY_SIZE)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = Instant.now()
        val subject = X500Name(
            "CN=OpenBank Document Service (DEV-ONLY EPHEMERAL), O=OpenBank, OU=openbank-document-service",
        )
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(Duration.ofMinutes(EPHEMERAL_NOT_BEFORE_SKEW_MINUTES))),
            Date.from(now.plus(Duration.ofDays(EPHEMERAL_VALIDITY_DAYS))),
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
        )
    }

    private companion object {
        const val BC_PROVIDER = "BC"
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        const val RSA_KEY_SIZE = 2048
        const val EPHEMERAL_VALIDITY_DAYS = 365L
        const val EPHEMERAL_NOT_BEFORE_SKEW_MINUTES = 5L
        const val ORGANIZATION_NAME = "OpenBank"
        const val SEAL_REASON = "Document signed via OpenBank signature ceremony"
    }
}
