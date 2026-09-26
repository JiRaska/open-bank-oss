// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local AES-256-GCM [FieldProtector] (ADR-0320 P3) for tests and dev, emitting the same
 * `vault:v<N>:` envelope as Transit so code under test handles key versions identically.
 * Payload is `base64(iv || ciphertext+tag)`. It is NOT byte-compatible with Transit ciphertext
 * (different key material by construction) — only the envelope is shared.
 *
 * @param keys key versions to 32-byte AES keys; the highest version encrypts.
 */
class LocalAesGcmFieldProtector(keys: Map<Int, ByteArray>, tokenizationPepper: ByteArray) : FieldProtector {

    private val keys: Map<Int, ByteArray> = keys.mapValues { it.value.copyOf() }
    private val latest: Int
    private val tokenizer = BlindIndexTokenizer(tokenizationPepper)
    private val random = SecureRandom()

    init {
        require(this.keys.isNotEmpty()) { "at least one key version is required" }
        require(this.keys.all { (v, k) -> v >= 1 && k.size == KEY_BYTES }) { "keys must be AES-256 with version >= 1" }
        latest = this.keys.keys.max()
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray?): String = seal(latest, plaintext, aad)

    override fun decrypt(ciphertext: String, aad: ByteArray?): ByteArray {
        val parsed = TransitCiphertext.parse(ciphertext)
        val key = keys[parsed.keyVersion]
            ?: throw FieldProtectionException("unknown key version v${parsed.keyVersion}")
        val raw = try {
            Base64.getDecoder().decode(parsed.payload)
        } catch (e: IllegalArgumentException) {
            throw FieldProtectionException("malformed ciphertext payload", e)
        }
        if (raw.size <= IV_BYTES) throw FieldProtectionException("malformed ciphertext payload")
        return try {
            cipher(Cipher.DECRYPT_MODE, key, raw.copyOfRange(0, IV_BYTES), aad).doFinal(
                raw,
                IV_BYTES,
                raw.size - IV_BYTES,
            )
        } catch (e: GeneralSecurityException) {
            throw FieldProtectionException("decrypt failed (wrong key or associated data)", e)
        }
    }

    override fun rewrap(ciphertext: String, aad: ByteArray?): String = seal(latest, decrypt(ciphertext, aad), aad)

    override fun tokenize(value: String): String = tokenizer.tokenize(value)

    private fun seal(version: Int, plaintext: ByteArray, aad: ByteArray?): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val sealed = cipher(Cipher.ENCRYPT_MODE, keys.getValue(version), iv, aad).doFinal(plaintext)
        return TransitCiphertext(version, Base64.getEncoder().encodeToString(iv + sealed)).render()
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray?): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            if (aad != null) updateAAD(aad)
        }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
