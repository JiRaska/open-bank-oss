// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.regex.Pattern

/**
 * JWT signing primitives for the push adapters, built on the JDK only (no external JOSE
 * library — keeps the supply-chain surface minimal for a money-adjacent service).
 *
 * - FCM uses RS256 (OAuth2 service-account assertion → access token).
 * - APNs uses ES256 and requires the raw JOSE/P1363 signature (R‖S, 32 bytes each), not the
 *   DER encoding the JDK emits — [signEs256] performs that conversion.
 */
internal object PushCrypto {

    private val PEM_BOUNDARY = Pattern.compile("-----(BEGIN|END)[^-]+-----")
    private val WHITESPACE = Pattern.compile("\\s")

    /** Parse a PKCS#8 PEM private key. `algorithm` is "RSA" (FCM) or "EC" (APNs). */
    fun parsePkcs8PrivateKey(pem: String, algorithm: String): PrivateKey {
        val base64 = WHITESPACE.matcher(PEM_BOUNDARY.matcher(pem).replaceAll("")).replaceAll("")
        val der = Base64.getDecoder().decode(base64)
        val spec = java.security.spec.PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance(algorithm).generatePrivate(spec)
    }

    fun b64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun signRs256(signingInput: String, key: PrivateKey): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(key)
        sig.update(signingInput.toByteArray(Charsets.UTF_8))
        return b64Url(sig.sign())
    }

    fun signEs256(signingInput: String, key: PrivateKey): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(key)
        sig.update(signingInput.toByteArray(Charsets.UTF_8))
        return b64Url(derToP1363(sig.sign(), 32))
    }

    /**
     * Convert a DER-encoded ECDSA signature (SEQUENCE { INTEGER r, INTEGER s }) into the
     * fixed-width JOSE concatenation R‖S that ES256 (and APNs) require.
     */
    internal fun derToP1363(der: ByteArray, partLen: Int): ByteArray {
        // SEQUENCE
        var offset = 0
        require(der[offset++].toInt() and 0xff == 0x30) { "Invalid DER: not a SEQUENCE" }
        // length (skip; may be short or long form)
        var len = der[offset++].toInt() and 0xff
        if (len and 0x80 != 0) {
            val numBytes = len and 0x7f
            len = 0
            repeat(numBytes) { len = (len shl 8) or (der[offset++].toInt() and 0xff) }
        }
        val r = readDerInteger(der, offset).also { offset = it.second }.first
        val s = readDerInteger(der, offset).first
        val out = ByteArray(partLen * 2)
        copyFixed(r, out, 0, partLen)
        copyFixed(s, out, partLen, partLen)
        return out
    }

    private fun readDerInteger(der: ByteArray, start: Int): Pair<ByteArray, Int> {
        var offset = start
        require(der[offset++].toInt() and 0xff == 0x02) { "Invalid DER: not an INTEGER" }
        val length = der[offset++].toInt() and 0xff
        val value = der.copyOfRange(offset, offset + length)
        return value to (offset + length)
    }

    private fun copyFixed(value: ByteArray, dest: ByteArray, destPos: Int, partLen: Int) {
        // BigInteger handles the DER sign byte / leading zeros cleanly.
        val magnitude = BigInteger(1, value).toByteArray().let {
            if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        require(magnitude.size <= partLen) { "ECDSA integer longer than $partLen bytes" }
        System.arraycopy(magnitude, 0, dest, destPos + (partLen - magnitude.size), magnitude.size)
    }
}
