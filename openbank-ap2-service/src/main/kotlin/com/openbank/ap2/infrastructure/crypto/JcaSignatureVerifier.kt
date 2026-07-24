// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.infrastructure.crypto

import com.openbank.ap2.application.port.out.SignatureVerifier
import com.openbank.ap2.domain.MandateSignatureAlgorithm
import jakarta.enterprise.context.ApplicationScoped
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a mandate signature with the JDK's own JCA — the exact primitive openbank-sca-service's
 * device-approval path already trusts (ADR-0193 §1), so no JOSE/VC dependency enters the crypto
 * surface for phase 1. Public keys are X.509 SubjectPublicKeyInfo (base64); the signing input is
 * the UTF-8 bytes that were signed.
 *
 * ES256 → SHA256withECDSA (DER-encoded signature), ED25519 → Ed25519 (native since JDK 15). JOSE
 * compact R||S ↔ DER transcoding, if a presented VC uses raw JOSE signatures, is an edge-adapter
 * concern deferred to phase 2 — this port verifies the platform's canonical encoding.
 */
@ApplicationScoped
class JcaSignatureVerifier : SignatureVerifier {

    override fun verify(
        algorithm: MandateSignatureAlgorithm,
        publicKeySpkiB64: String,
        signingInput: String,
        signatureB64: String,
    ): Boolean {
        val keyBytes = Base64.getDecoder().decode(publicKeySpkiB64)
        val sigBytes = Base64.getDecoder().decode(signatureB64)
        val (keyAlg, sigAlg) = when (algorithm) {
            MandateSignatureAlgorithm.ES256 -> "EC" to "SHA256withECDSA"
            MandateSignatureAlgorithm.ED25519 -> "Ed25519" to "Ed25519"
        }
        val publicKey = KeyFactory.getInstance(keyAlg).generatePublic(X509EncodedKeySpec(keyBytes))
        return Signature.getInstance(sigAlg).run {
            initVerify(publicKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            verify(sigBytes)
        }
    }
}
