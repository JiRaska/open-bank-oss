// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * QSEAL message-signature verification (ADR-0090 P4): real JCA round-trips — a signature made with
 * a private key verifies with its public key, a tampered signing string / digest is rejected, and
 * the `Signature` header parser and X.509 PEM extraction behave.
 */
class QsealVerifierTest {

    @Test
    fun `parseSignature reads keyId, algorithm, headers and signature`() {
        val header = "keyId=\"tpp-1\",algorithm=\"rsa-sha256\",headers=\"digest x-request-id\",signature=\"AAAA\""
        val p = QsealVerifier.parseSignature(header)

        assertThat(p).isNotNull
        assertThat(p!!.keyId).isEqualTo("tpp-1")
        assertThat(p.algorithm).isEqualTo("rsa-sha256")
        assertThat(p.headers).containsExactly("digest", "x-request-id")
        assertThat(p.signature).isEqualTo("AAAA")
    }

    @Test
    fun `parseSignature returns null on malformed header`() {
        assertThat(QsealVerifier.parseSignature(null)).isNull()
        assertThat(QsealVerifier.parseSignature("garbage")).isNull()
        assertThat(QsealVerifier.parseSignature("algorithm=\"rsa-sha256\"")).isNull()
    }

    @Test
    fun `digestMatches accepts the correct SHA-256 and rejects tampering`() {
        val body = """{"amount":"100.00"}""".toByteArray()
        val good = "SHA-256=" + Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(body),
        )
        assertThat(QsealVerifier.digestMatches(body, good)).isTrue()
        assertThat(QsealVerifier.digestMatches(body, "SHA-256=wrong")).isFalse()
        assertThat(QsealVerifier.digestMatches(body, null)).isFalse()
    }

    @Test
    fun `signatureValid verifies a real RSA signature and rejects a tampered string`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val params = QsealVerifier.SignatureParams("k", "rsa-sha256", listOf("digest"), "")
        val signingString = "digest: SHA-256=abc"

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update(signingString.toByteArray())
        val sig = Base64.getEncoder().encodeToString(signer.sign())

        val signed = params.copy(signature = sig)
        assertThat(QsealVerifier.signatureValid(signingString, signed, kp.public)).isTrue()
        assertThat(QsealVerifier.signatureValid("digest: SHA-256=TAMPERED", signed, kp.public)).isFalse()
    }

    @Test
    fun `signatureValid rejects an unknown algorithm`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val params = QsealVerifier.SignatureParams("k", "md5-magic", listOf("digest"), "AAAA")
        assertThat(QsealVerifier.signatureValid("x", params, kp.public)).isFalse()
    }

    @Test
    fun `publicKeyFromPem parses an X509 certificate and rejects junk`() {
        assertThat(QsealVerifier.publicKeyFromPem(TEST_CERT_PEM)).isNotNull
        assertThat(QsealVerifier.publicKeyFromPem("not a cert")).isNull()
        assertThat(QsealVerifier.publicKeyFromPem(null)).isNull()
    }

    private companion object {
        val TEST_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDNzCCAh+gAwIBAgIUVuoID2/hl/SN94AI1pnt9HRgjUwwDQYJKoZIhvcNAQEL
            BQAwKzERMA8GA1UEAwwIdGVzdC10cHAxFjAUBgNVBAoMDU9wZW5CYW5rIFRlc3Qw
            HhcNMjYwNjE1MDkzOTE3WhcNMzYwNjEyMDkzOTE3WjArMREwDwYDVQQDDAh0ZXN0
            LXRwcDEWMBQGA1UECgwNT3BlbkJhbmsgVGVzdDCCASIwDQYJKoZIhvcNAQEBBQAD
            ggEPADCCAQoCggEBAK6h7gRbhDO6NGDpRPgiY0iG4C4xIuN0+KXJar6+MkudQmDT
            eKsfxQTDLKys7zSqJYvSHis31ny4sdbtYtp7m0lfjn3W9elzuRjw3d+CGV1mR8Th
            D7R4tAL6S6XjHJXYYfbby43f+Pazha9fEeChs+i1ScUZ0qn1yqIL6a2OF/TX+s5q
            04NfoLUbCP1nHzjLLzdfDpI+UsF5kxVLxumC/aUUn+D73yaRRpNqDS43a8tQ8pwt
            6FXO4N03oanZv/KTMZMFHv8wlSWUXF8yjEdFr1aLA8sAjjivQlKvPD7oDzSCloqQ
            CRQ8pWEKv85tDczGCR7x6UAw8RP2WHrdyTG0OjUCAwEAAaNTMFEwHQYDVR0OBBYE
            FJZizV/VCBxepeQgv1txeWBh3maCMB8GA1UdIwQYMBaAFJZizV/VCBxepeQgv1tx
            eWBh3maCMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAA2YmCqJ
            hMGYMa8DquIgrVZuDAUGSej7khUCIeNu0+OyNeFTxC9cBJD3Jg2fANWcKH5nd2Im
            u1OsBjLTr2KjPK65zzt1cpe1DlWPAAFXvdgj5unZPvFJsshLgUuyNHZSNXpcnhp6
            1vUpg0r/0/I3UQjrghV7KiBB5oBJwXrmD9WmdO+jo+VTAVzVEnVfMtQG3WCCjntY
            ekqaEINvEJqNBmLeFB859mqCQsNqb/vdzOkgtkx9AYAV06HSJy6X5bhanPAACdgm
            sDPYtRJ06KpxI4D1impv3oXQZMa/xf/U4WeumQ6zuccCRvRV/BKJnej4txDO17mI
            achzi3ItMtAffAU=
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
