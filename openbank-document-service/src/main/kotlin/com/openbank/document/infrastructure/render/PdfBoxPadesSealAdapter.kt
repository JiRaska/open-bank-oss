// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.application.port.out.SignatureSealPort
import com.openbank.document.domain.model.SignatureCeremony
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Optional

/**
 * Phase-1 [SignatureSealPort] adapter (ADR-0162 D4): applies the bank's institutional
 * **electronic seal** — a server-side **PAdES-B** signature (`SubFilter: ETSI.CAdES.detached`) —
 * using a STABLE, long-lived organizational identity (contrast with
 * [OpenBaoClientSignatureAdapter]'s per-signer one-time identity). Mechanics (PDFBox +
 * Bouncy Castle CMS/PKCS7) live in [PadesSigning], shared by both adapters.
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

    // Go-live gate (ADR-0162 D4 continued), shared with OpenBaoClientSignatureAdapter: when set, the
    // service refuses to start with a DEV-ONLY ephemeral seal identity — better to fail boot than to
    // apply seals that are worthless as evidence. Defaults off so the service is runnable before a
    // real organizational keystore is provisioned; flip OPENBANK_SIGNATURE_REQUIRE_TRUSTED_ISSUER=true
    // in the deployment env once it is.
    @ConfigProperty(name = "openbank.signature.require-trusted-issuer", defaultValue = "false")
    requireTrustedIssuer: Boolean,
) : SignatureSealPort {

    private val logger = Logger.getLogger(PdfBoxPadesSealAdapter::class.java)

    private val identity: SigningIdentity

    init {
        // Configured-but-not-yet-present (e.g. the gitops volume/env-var wiring merged before the
        // OpenBao KV secret was actually populated) is treated the SAME as not-configured-at-all:
        // both fall back to the ephemeral dev identity with a loud warning, rather than crashing
        // boot on a FileNotFoundException. Rollout order (infra wiring vs. secret population) must
        // never be able to crash-loop this service.
        identity = if (keystorePath.isPresent && Files.exists(Path.of(keystorePath.get()))) {
            loadFromKeystore(keystorePath.get(), keystorePassword.orElse(""))
        } else {
            check(!requireTrustedIssuer) {
                "openbank.signature.keystore-path is not configured (or the file is not present) " +
                    "while openbank.signature.require-trusted-issuer is set — refusing to start with " +
                    "a DEV-ONLY ephemeral seal identity. Configure a real organizational PKCS12 " +
                    "keystore (OpenBao KV, ADR-0162 D4 continued) before sealing real documents."
            }
            logger.warn(
                "openbank.signature.keystore-path is not configured, or the file does not exist yet " +
                    "at that path — PdfBoxPadesSealAdapter is generating an EPHEMERAL, in-memory, " +
                    "non-persisted self-signed X.509 certificate purely so the service is runnable " +
                    "out of the box. This is DEV-ONLY: every PAdES seal applied while this warning " +
                    "fires is worthless as evidence (the private key vanishes on restart and was " +
                    "never issued by any trusted CA). Production MUST configure " +
                    "openbank.signature.keystore-path / openbank.signature.keystore-password with a " +
                    "real organizational PKCS12 certificate (OpenBao KV, ADR-0162 D4 continued) " +
                    "before this service handles a real signature ceremony.",
            )
            PadesSigning.generateEphemeralIdentity("OpenBank Document Service (DEV-ONLY EPHEMERAL)")
        }
    }

    override suspend fun sealPades(pdf: ByteArray, ceremony: SignatureCeremony): ByteArray =
        withContext(Dispatchers.IO) {
            PadesSigning.applySignature(pdf, identity, ORGANIZATION_NAME, SEAL_REASON)
        }

    private fun loadFromKeystore(path: String, password: String): SigningIdentity {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(path).use { keyStore.load(it, password.toCharArray()) }
        val alias = keyStore.aliases().asSequence().first { keyStore.isKeyEntry(it) }
        val privateKey = keyStore.getKey(alias, password.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        val chain = keyStore.getCertificateChain(alias).map { it as X509Certificate }
        return SigningIdentity(privateKey, certificate, chain)
    }

    private companion object {
        const val ORGANIZATION_NAME = "OpenBank"
        const val SEAL_REASON = "Document signed via OpenBank signature ceremony"
    }
}
