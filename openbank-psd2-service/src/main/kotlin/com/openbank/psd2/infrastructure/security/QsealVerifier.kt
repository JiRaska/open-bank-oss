// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.security

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

/**
 * eIDAS **QSEAL** message-signature verification (ADR-0090 P4), following the Berlin Group
 * NextGenPSD2 profile of `draft-cavage-http-signatures`:
 *
 *  - `Digest: SHA-256=<base64(sha256(body))>` binds the request body,
 *  - `Signature: keyId="…",algorithm="rsa-sha256",headers="digest x-request-id",signature="<b64>"`
 *    signs the canonical signing string over the listed (lower-cased) header values,
 *  - `TPP-Signature-Certificate` carries the TPP's QSEAL X.509 certificate (PEM).
 *
 * Pure JCA — no third-party crypto. Stateless; all inputs are passed in so it is trivially testable
 * and side-effect free. The wiring filter decides advisory-vs-enforce; this object only answers
 * "is this signature valid?".
 */
object QsealVerifier {

    data class SignatureParams(
        val keyId: String,
        val algorithm: String,
        val headers: List<String>,
        val signature: String,
    )

    /** Parse the Berlin `Signature` header into its components, or null if malformed. */
    fun parseSignature(header: String?): SignatureParams? {
        if (header.isNullOrBlank()) return null
        val kv = header.split(",").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim().trim('"')
            k to v
        }.toMap()
        val keyId = kv["keyId"] ?: return null
        val algorithm = kv["algorithm"] ?: return null
        val signature = kv["signature"] ?: return null
        val headers = (kv["headers"] ?: "date").split(" ").filter { it.isNotBlank() }
        return SignatureParams(keyId, algorithm, headers, signature)
    }

    /** True iff `Digest` matches `SHA-256=base64(sha256(body))` (constant-time compare). */
    fun digestMatches(body: ByteArray, digestHeader: String?): Boolean {
        if (digestHeader.isNullOrBlank()) return false
        val computed = "SHA-256=" + Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(body),
        )
        return MessageDigest.isEqual(computed.toByteArray(), digestHeader.trim().toByteArray())
    }

    /** Canonical signing string: `name: value` per listed header (lower-cased), newline-joined. */
    fun signingString(params: SignatureParams, headerValues: Map<String, String>): String =
        params.headers.joinToString("\n") { h -> "${h.lowercase()}: ${headerValues[h.lowercase()].orEmpty()}" }

    /** Verify the signature over [signingString] with [publicKey] for the parsed algorithm. */
    fun signatureValid(signingString: String, params: SignatureParams, publicKey: PublicKey): Boolean {
        val jcaAlg = when (params.algorithm.lowercase()) {
            "rsa-sha256" -> "SHA256withRSA"
            "ecdsa-sha256" -> "SHA256withECDSA"
            else -> return false
        }
        return runCatching {
            val verifier = Signature.getInstance(jcaAlg)
            verifier.initVerify(publicKey)
            verifier.update(signingString.toByteArray())
            verifier.verify(Base64.getDecoder().decode(params.signature))
        }.getOrDefault(false)
    }

    /** Extract the public key from a PEM (or bare base64) X.509 certificate; null if unparseable. */
    fun publicKeyFromPem(pem: String?): PublicKey? {
        if (pem.isNullOrBlank()) return null
        return runCatching {
            val cleaned = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace(Regex("\\s"), "")
            val der = Base64.getDecoder().decode(cleaned)
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()).publicKey
        }.getOrNull()
    }
}
