// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.application.port.out.SignatureSealPort
import com.openbank.document.domain.model.SignatureCeremony
import io.quarkus.runtime.Startup
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
// @Startup, not merely @ApplicationScoped: this bean's init decides whether the seal identity is a
// real organizational one or a DEV-ONLY ephemeral throwaway, and says so in a loud warning. A
// CDI-lazy bean would defer that decision — and the warning — to the FIRST signature ceremony, which
// is the one moment it is too late to be a warning: until then the log is clean and the service
// reports UP, so nothing tells an operator the seals it is about to apply are worthless as evidence.
// It is also what makes require-trusted-issuer a boot gate rather than a first-request gate.
@Startup
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

    // The seal-time half of the same gate (ADR-0172 D2), and the one that actually protects evidence.
    //
    // `require-trusted-issuer` is a BOOT gate and defaults off on purpose: the rollout order (gitops
    // env/volume wiring vs. OpenBao KV seeding) must never be able to crash-loop this service. That
    // is a good constraint — but it left the fallback with nothing stopping it, so a service whose
    // keystore secret failed to materialise would boot fine, log one WARN, and then happily seal
    // documents with a throwaway cert. A misconfiguration silently produced legally worthless
    // evidence that looked successful.
    //
    // So this gate is seal-time, not boot-time: booting on an ephemeral identity stays allowed
    // (rollout-safe), but SIGNING with one does not. Defaults to `false` — fail closed — and is
    // overridden to `true` only in %dev/%test (application.yaml), so the service is still runnable
    // out of the box. Any deployment that genuinely wants throwaway seals must say so out loud with
    // OPENBANK_SIGNATURE_ALLOW_EPHEMERAL_SEALS=true, which is a reviewable line in gitops rather
    // than an invisible code default.
    @ConfigProperty(name = "openbank.signature.allow-ephemeral-seals", defaultValue = "false")
    private val allowEphemeralSeals: Boolean,
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
            // Fail CLOSED before touching the PDF (ADR-0172 D2). An ephemeral identity produces a
            // signature that is cryptographically well-formed and legally worthless — the failure
            // mode that matters, because the output looks exactly like success. Refusing here means
            // the ceremony errors loudly and no false evidence is ever written; the alternative is a
            // sealed PDF nobody can rely on and nobody noticed.
            check(!identity.ephemeral || allowEphemeralSeals) {
                "Refusing to apply an institutional PAdES seal with a DEV-ONLY ephemeral identity: " +
                    "the resulting signature would be worthless as evidence (self-signed, never " +
                    "issued by any trusted CA, private key gone on restart). Provision the real " +
                    "organizational PKCS12 keystore at openbank.signature.keystore-path (seeded into " +
                    "OpenBao KV per docs/runbooks/0008-openbao-document-signing-pki.md), or set " +
                    "openbank.signature.allow-ephemeral-seals=true to accept throwaway seals in a " +
                    "non-production environment. ceremonyId=${ceremony.id}"
            }
            // Idempotent: don't re-apply the institutional seal if a retry re-enters after the seal
            // was already written but the ceremony's completion failed to persist.
            if (PadesSigning.hasSignatureNamed(pdf, ORGANIZATION_NAME)) {
                pdf
            } else {
                PadesSigning.applySignature(pdf, identity, ORGANIZATION_NAME, SEAL_REASON)
            }
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
