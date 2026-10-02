// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import java.util.HexFormat

/**
 * Lowercase hex rendering of a byte array — the one place the libs turn a digest into text.
 *
 * Every hash the libs persist or put on the wire (audit hash chain, idempotency fingerprint,
 * blind index, decision-input digest, docs ETag, compliance-pack content hash, policy snapshot
 * hash) is rendered with this. The output is byte-identical to the previous per-byte idiom
 * `bytes.joinToString("") { "%02x".format(it) }`: two lowercase digits per byte, no separator,
 * `0x00` as `00` and `0xff` as `ff` (pinned by `HexOutputGoldenVectorTest` in libs-domain and
 * libs-lending, and by `HexTest`'s property over random arrays). What changed is the cost —
 * `String.format` parses the pattern and allocates a `Formatter` per byte, i.e. 32 times per
 * SHA-256, on every mutating request and every audit event.
 *
 * Pure JDK (`java.util.HexFormat`), so the domain module stays framework-free (ADR-0122).
 */
object Hex {
    private val LOWER: HexFormat = HexFormat.of()

    /** Lowercase hex, two digits per byte, no separator; the empty array renders as `""`. */
    fun lower(bytes: ByteArray): String = LOWER.formatHex(bytes)
}
