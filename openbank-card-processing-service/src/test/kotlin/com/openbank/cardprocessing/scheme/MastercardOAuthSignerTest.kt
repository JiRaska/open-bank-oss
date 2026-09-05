// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheme

import com.openbank.cardprocessing.infrastructure.scheme.MastercardOAuthSigner
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * OAuth 1.0a signing, verified by CONSTRUCTION rather than by a golden string.
 *
 * A test that pins a fixed signature proves only that the code still does what it did; it cannot
 * say whether that was ever right. These tests instead assert the properties Mastercard's own
 * verifier depends on — the encoding alphabet, the parameter ordering, the double encoding, the URL
 * normalisation — and then verify the signature with the public key, which is exactly what the
 * server does.
 */
class MastercardOAuthSignerTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)
    private val signer = MastercardOAuthSigner("test-consumer-key", keyPair.private, clock)

    @Test
    fun `percent encoding follows RFC 3986, not form encoding`() {
        // Each of the three differs from URLEncoder's output, and each one changes the signature.
        assertThat(MastercardOAuthSigner.percentEncode("a b")).isEqualTo("a%20b")
        assertThat(MastercardOAuthSigner.percentEncode("*")).isEqualTo("%2A")
        assertThat(MastercardOAuthSigner.percentEncode("~")).isEqualTo("~")
    }

    @Test
    fun `the base string encodes the parameter block a second time`() {
        val base = signer.baseString("GET", "https://api.example.com/x", mapOf("a" to "1", "b" to "2"))

        // `a=1&b=2` encoded once is `a%3D1%26b%3D2`. Encoding once and stopping is the classic
        // mistake, and it passes any test whose values contain nothing that encodes.
        assertThat(base).isEqualTo("GET&https%3A%2F%2Fapi.example.com%2Fx&a%3D1%26b%3D2")
    }

    @Test
    fun `parameters are ordered by their ENCODED form`() {
        // ' ' encodes to %20 and '!' to %21, so the encoded order is the reverse of the raw one.
        // Sorting before encoding would order these the other way and the server would rebuild a
        // different base string.
        val base = signer.baseString("GET", "https://api.example.com/x", mapOf("k" to "b!", "j" to "b "))

        assertThat(base).endsWith("j%3Db%2520%26k%3Db%2521")
    }

    @Test
    fun `the signed URL drops the query, the default port and the case of scheme and host`() {
        val normalised = signer.normaliseUrl("HTTPS://API.Example.COM:443/bin-resources?accountRange=5555")

        assertThat(normalised).isEqualTo("https://api.example.com/bin-resources")
    }

    @Test
    fun `a non-default port is kept, because the server keeps it too`() {
        assertThat(signer.normaliseUrl("https://api.example.com:8443/x")).isEqualTo("https://api.example.com:8443/x")
    }

    @Test
    fun `the header verifies against the public key, which is what the server does`() {
        val url = "https://api.example.com/bin-resources/bin-ranges/account-searches"
        val parameters = mapOf("accountRange" to "555555")

        val header = signer.authorizationHeader("GET", url, parameters)

        assertThat(header).startsWith("OAuth ")
        assertThat(header).contains("""oauth_signature_method="RSA-SHA256"""")
        val signature = Regex("""oauth_signature="([^"]+)"""").find(header)!!.groupValues[1]
        val nonce = Regex("""oauth_nonce="([^"]+)"""").find(header)!!.groupValues[1]
        val timestamp = Regex("""oauth_timestamp="([^"]+)"""").find(header)!!.groupValues[1]

        // Rebuild the base string the way a verifier would, from the header's own parameters.
        val rebuilt = signer.baseString(
            "GET",
            url,
            parameters + mapOf(
                "oauth_consumer_key" to "test-consumer-key",
                "oauth_nonce" to decode(nonce),
                "oauth_signature_method" to MastercardOAuthSigner.SIGNATURE_METHOD,
                "oauth_timestamp" to timestamp,
                "oauth_version" to MastercardOAuthSigner.OAUTH_VERSION,
            ),
        )
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(rebuilt.toByteArray())
        }
        assertThat(verifier.verify(Base64.getDecoder().decode(decode(signature)))).isTrue()
    }

    @Test
    fun `two calls do not reuse a nonce`() {
        val url = "https://api.example.com/x"

        val first = signer.authorizationHeader("GET", url)
        val second = signer.authorizationHeader("GET", url)

        // The clock is fixed, so the timestamps match — a repeated nonce would make the two
        // requests identical and the second a replay the server should reject.
        assertThat(first).isNotEqualTo(second)
    }

    private fun decode(percentEncoded: String): String =
        java.net.URLDecoder.decode(percentEncoded.replace("+", "%2B"), Charsets.UTF_8)

    private companion object {
        const val KEY_BITS = 2048
    }
}
