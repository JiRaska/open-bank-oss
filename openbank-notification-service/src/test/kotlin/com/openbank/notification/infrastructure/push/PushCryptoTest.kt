// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.notification.infrastructure.push

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PushCryptoTest {

    @Test
    fun `b64Url is url-safe and unpadded`() {
        // 0xFB 0xFF would yield "+/8=" in standard base64 → "-_8" url-safe, no padding.
        val encoded = PushCrypto.b64Url(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))
        assertThat(encoded).doesNotContain("+", "/", "=")
        assertThat(encoded).isEqualTo("-_8")
    }

    @Test
    fun `parsePkcs8PrivateKey round-trips an EC key`() {
        val pair = ecKeyPair()
        val pem = pkcs8Pem(pair)
        val parsed = PushCrypto.parsePkcs8PrivateKey(pem, "EC")
        assertThat(parsed.encoded).isEqualTo(pair.private.encoded)
    }

    @Test
    fun `parsePkcs8PrivateKey round-trips an RSA key`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val parsed = PushCrypto.parsePkcs8PrivateKey(pkcs8Pem(pair), "RSA")
        assertThat(parsed.encoded).isEqualTo(pair.private.encoded)
    }

    @Test
    fun `signRs256 produces a signature the public key verifies`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val input = "header.claims"
        val sig = Base64.getUrlDecoder().decode(PushCrypto.signRs256(input, pair.private))
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(pair.public)
            update(input.toByteArray())
        }
        assertThat(verifier.verify(sig)).isTrue()
    }

    @Test
    fun `signEs256 produces a 64-byte JOSE signature the public key verifies`() {
        val pair = ecKeyPair()
        val input = "header.claims"
        val joseSig = Base64.getUrlDecoder().decode(PushCrypto.signEs256(input, pair.private))
        // ES256 JOSE/P1363 is fixed-width R‖S, 32 bytes each.
        assertThat(joseSig).hasSize(64)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(input.toByteArray())
        }
        // JCA verifies DER, so convert the P1363 back to DER for the check.
        assertThat(verifier.verify(p1363ToDer(joseSig))).isTrue()
    }

    @Test
    fun `derToP1363 left-pads short integers to fixed width`() {
        // Many signatures exercise the leading-zero / short-integer branch of copyFixed.
        val pair = ecKeyPair()
        repeat(50) {
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update("payload-$it".toByteArray())
                sign()
            }
            assertThat(PushCrypto.derToP1363(der, 32)).hasSize(64)
        }
    }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun pkcs8Pem(pair: KeyPair): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
    }

    /** Inverse of PushCrypto.derToP1363, for verification only (handles R/S < 128 bytes). */
    private fun p1363ToDer(p1363: ByteArray): ByteArray {
        val half = p1363.size / 2
        val r = BigInteger(1, p1363.copyOfRange(0, half)).toByteArray()
        val s = BigInteger(1, p1363.copyOfRange(half, p1363.size)).toByteArray()
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
