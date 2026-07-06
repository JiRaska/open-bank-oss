// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * QSEAL message-signature gate (ADR-0090 P4): only the Berlin write surface (`POST v1/payments` or
 * `v1/consents`) is checked; advisory mode (default) logs but never blocks, enforce mode rejects.
 */
class QsealSignatureFilterTest {

    private val testCertPem = """
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

    private fun ctxFor(
        method: String,
        path: String,
        signatureHeader: String?,
        certPem: String?,
        digestHeader: String?,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>(relaxed = true)
        val uriInfo = mockk<UriInfo>()
        every { uriInfo.path } returns path
        every { ctx.uriInfo } returns uriInfo
        every { ctx.method } returns method
        every { ctx.entityStream } returns ByteArrayInputStream(body)
        every { ctx.entityStream = any() } returns Unit
        every { ctx.getHeaderString("Signature") } returns signatureHeader
        every { ctx.getHeaderString("TPP-Signature-Certificate") } returns certPem
        every { ctx.getHeaderString("Digest") } returns digestHeader
        extraHeaders.forEach { (k, v) -> every { ctx.getHeaderString(k) } returns v }
        return ctx
    }

    @Test
    fun `non-write paths are not intercepted`() {
        val filter = QsealSignatureFilter(enforce = true)
        val ctx = ctxFor("GET", "v1/accounts", null, null, null, ByteArray(0))

        filter.filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `GET on the payments path is not intercepted even when it carries a body`() {
        val filter = QsealSignatureFilter(enforce = true)
        val ctx = ctxFor("GET", "v1/payments/sepa-credit-transfers/p-1/status", null, null, null, ByteArray(0))

        filter.filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `advisory mode allows a missing signature through without aborting`() {
        val filter = QsealSignatureFilter(enforce = false)
        val ctx = ctxFor("POST", "v1/payments/sepa-credit-transfers", null, null, null, "{}".toByteArray())

        filter.filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `enforce mode rejects a missing signature with 401 SIGNATURE_INVALID`() {
        val filter = QsealSignatureFilter(enforce = true)
        val ctx = ctxFor("POST", "v1/consents", null, null, null, "{}".toByteArray())
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(401)
    }

    @Test
    fun `enforce mode rejects a bad digest`() {
        val filter = QsealSignatureFilter(enforce = true)
        val body = """{"amount":"1.00"}""".toByteArray()
        val sigHeader = "keyId=\"tpp-1\",algorithm=\"rsa-sha256\",headers=\"digest\",signature=\"AAAA\""
        val ctx = ctxFor("POST", "v1/payments/sepa-credit-transfers", sigHeader, testCertPem, "SHA-256=wrong", body)
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(401)
    }

    @Test
    fun `enforce mode rejects a signature that does not verify against the certificate`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = """{"amount":"1.00"}""".toByteArray()
        val digest = "SHA-256=" + Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(body),
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update("digest: $digest".toByteArray())
        val sig = Base64.getEncoder().encodeToString(signer.sign())
        val sigHeader = "keyId=\"tpp-1\",algorithm=\"rsa-sha256\",headers=\"digest\",signature=\"$sig\""

        // testCertPem's public key does not correspond to kp.private, so verification fails.
        val filter = QsealSignatureFilter(enforce = true)
        val ctx = ctxFor("POST", "v1/payments/sepa-credit-transfers", sigHeader, testCertPem, digest, body)
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(401)
    }
}
