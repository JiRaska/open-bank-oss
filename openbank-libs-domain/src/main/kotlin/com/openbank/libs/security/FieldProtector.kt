// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.openbank.libs.identity.BlindIndex

/**
 * Field-protection port (ADR-0320 P3): reversible encryption of a single sensitive field, bound to
 * associated data, plus a deterministic one-way token for equality lookup.
 *
 * Ciphertexts use the OpenBao/Vault Transit wire format `vault:v<N>:<base64>` so a value written by
 * any adapter carries the key version it was sealed under, and a key rotation followed by [rewrap]
 * moves it forward without the plaintext ever leaving the key holder.
 *
 * **Fail closed.** Every failure — unreachable key service, timeout, non-2xx, wrong key, AAD
 * mismatch, malformed ciphertext — surfaces as [FieldProtectionException]; no method ever returns
 * the input unchanged or an empty value in place of a result.
 *
 * **AAD.** [aad] is authenticated, not encrypted: decrypting with a different [aad] than the value
 * was sealed with fails. Bind it to the owning row (e.g. `"card:" + cardId`) so a ciphertext copied
 * to another row does not decrypt there.
 */
interface FieldProtector {

    /** Seals [plaintext] under the current key version, returning `vault:v<N>:...`. */
    fun encrypt(plaintext: ByteArray, aad: ByteArray? = null): String

    /** Opens [ciphertext] sealed by [encrypt] with the same [aad]. */
    fun decrypt(ciphertext: String, aad: ByteArray? = null): ByteArray

    /** Re-seals [ciphertext] under the key's latest version without exposing the plaintext. */
    fun rewrap(ciphertext: String, aad: ByteArray? = null): String

    /**
     * Deterministic, one-way token for equality search. Delegates to the ADR-0189/ADR-0072 keyed
     * blind index ([BlindIndex]) — there is deliberately no second HMAC scheme.
     */
    fun tokenize(value: String): String
}

/** Thrown for every field-protection failure; callers must treat it as "no value available". */
class FieldProtectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Parsed `vault:v<N>:<payload>` ciphertext. */
data class TransitCiphertext(val keyVersion: Int, val payload: String) {

    fun render(): String = "$PREFIX$keyVersion:$payload"

    companion object {
        const val PREFIX = "vault:v"
        private val FORMAT = Regex("^vault:v([1-9][0-9]{0,8}):([A-Za-z0-9+/=]+)$")

        /** Parses [value]; throws [FieldProtectionException] (never echoing [value]) when malformed. */
        fun parse(value: String): TransitCiphertext {
            val match = FORMAT.matchEntire(value)
                ?: throw FieldProtectionException("ciphertext is not in vault:v<N>:<base64> format")
            return TransitCiphertext(match.groupValues[1].toInt(), match.groupValues[2])
        }
    }
}

/** [BlindIndex]-backed tokenization shared by every [FieldProtector] adapter. */
class BlindIndexTokenizer(pepper: ByteArray) {
    private val pepper: ByteArray = pepper.copyOf()

    init {
        require(this.pepper.isNotEmpty()) { "tokenization pepper must not be empty" }
    }

    fun tokenize(value: String): String = BlindIndex.compute(pepper, value)
}
