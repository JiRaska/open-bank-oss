// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.identity

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed blind index for deterministic equality matching of a sensitive identifier without
 * storing it reversibly (ADR-0072).
 *
 * A blind index is `HMAC-SHA256(pepper, value)` rendered as lowercase hex. Unlike the
 * non-deterministic `pgcrypto` column encryption used for the plaintext at rest, the index is
 * stable for a given (pepper, value), so it can carry a `UNIQUE` constraint and be
 * equality-searched — while remaining one-way and unguessable without the pepper.
 *
 * The [pepper] is a service-held secret, **distinct** from the column encryption key, sourced
 * from Vault. Rotating it changes every index, so it is versioned by the caller
 * (`index_key_version`) and a rotation triggers a re-index migration. The pepper is never the
 * encryption key: compromise of one must not compromise the other.
 *
 * Callers must normalize [value] to its canonical form first (e.g. [RodneCislo.Parsed.canonical])
 * so that cosmetically different inputs (slash, spaces) collapse to the same index.
 */
object BlindIndex {

    fun compute(pepper: ByteArray, value: String): String {
        require(pepper.isNotEmpty()) { "pepper must not be empty" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper, "HmacSHA256"))
        val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}
