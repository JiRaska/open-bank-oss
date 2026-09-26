// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.openbank.libs.identity.BlindIndex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 *
 * **Key types.** Only NON-DERIVED AEAD keys are supported (Transit `aes256-gcm96`,
 * `aes128-gcm96`, `chacha20-poly1305` with `derived=false`). A derived key would need Transit's
 * `context` on every call, which this port does not carry; a non-AEAD key type ignores
 * `associated_data` and would silently drop the row binding described above.
 */
interface FieldProtector {

    /** Seals [plaintext] under the current key version, returning `vault:v<N>:...`. */
    fun encrypt(plaintext: ByteArray, aad: ByteArray? = null): String

    /** Opens [ciphertext] sealed by [encrypt] with the same [aad]. */
    fun decrypt(ciphertext: String, aad: ByteArray? = null): ByteArray

    /**
     * Re-seals [ciphertext] under the key's latest version. Without [aad] the Transit adapter uses
     * the server-side `rewrap` endpoint (plaintext never leaves OpenBao). OpenBao's `rewrap` takes
     * no `associated_data` (measured against openbao 2.5.4: an AAD-bound ciphertext answers
     * `message authentication failed` whether or not the field is sent), so with [aad] the adapter
     * decrypts and re-encrypts, and the plaintext transits this process for that call.
     */
    fun rewrap(ciphertext: String, aad: ByteArray? = null): String

    /**
     * Deterministic, one-way token for equality search. Delegates to the ADR-0189/ADR-0072 keyed
     * blind index ([BlindIndex]) — there is deliberately no second HMAC scheme.
     */
    fun tokenize(value: String): String

    /**
     * Domain-separated token: `HMAC-SHA256(pepper, domain || 0x00 || value)`, so equal values in
     * two different fields (e.g. `"pan"` vs `"iban"`) do not produce linkable tokens. Not
     * compatible with [tokenize]/[BlindIndex] — pick one per column and keep it.
     */
    fun tokenize(value: String, domain: String): String
}

/** Thrown for every field-protection failure; callers must treat it as "no value available". */
class FieldProtectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Parsed `vault:v<N>:<payload>` ciphertext. */
data class TransitCiphertext(val keyVersion: Int, val payload: String) {

    fun render(): String = "$PREFIX$keyVersion:$payload"

    companion object {
        const val PREFIX = "vault:v"

        /** Upper bound on the base64 payload; a single field never legitimately approaches it. */
        const val MAX_PAYLOAD_CHARS = 65_536

        // Canonical padded base64: `=` only as the final one or two characters of the last quad.
        private val FORMAT = Regex(
            "^vault:v([1-9][0-9]{0,8}):((?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{4}))$",
        )

        /** Parses [value]; throws [FieldProtectionException] (never echoing [value]) when malformed. */
        fun parse(value: String): TransitCiphertext {
            if (value.length > PREFIX.length + MAX_VERSION_DIGITS + 1 + MAX_PAYLOAD_CHARS) {
                throw FieldProtectionException("ciphertext exceeds $MAX_PAYLOAD_CHARS payload characters")
            }
            val match = FORMAT.matchEntire(value)
                ?: throw FieldProtectionException("ciphertext is not in vault:v<N>:<base64> format")
            if (match.groupValues[2].length > MAX_PAYLOAD_CHARS) {
                throw FieldProtectionException("ciphertext exceeds $MAX_PAYLOAD_CHARS payload characters")
            }
            return TransitCiphertext(match.groupValues[1].toInt(), match.groupValues[2])
        }

        private const val MAX_VERSION_DIGITS = 9
    }
}

/** [BlindIndex]-backed tokenization shared by every [FieldProtector] adapter. */
class BlindIndexTokenizer(pepper: ByteArray) {
    private val pepper: ByteArray = pepper.copyOf()

    init {
        require(this.pepper.isNotEmpty()) { "tokenization pepper must not be empty" }
    }

    fun tokenize(value: String): String = BlindIndex.compute(pepper, value)

    /** `HMAC-SHA256(pepper, domain || 0x00 || value)`, lowercase hex. */
    fun tokenize(value: String, domain: String): String {
        require(domain.isNotEmpty() && '\u0000' !in domain) { "token domain must be non-empty and contain no NUL" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(pepper, HMAC))
        mac.update(domain.toByteArray(Charsets.UTF_8))
        mac.update(0.toByte())
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and BYTE_MASK)
        }
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val BYTE_MASK = 0xFF
    }
}
