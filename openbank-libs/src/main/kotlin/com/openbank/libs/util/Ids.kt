// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.util

import java.security.SecureRandom
import java.util.UUID

/**
 * Identifier helpers for the OpenBank platform.
 *
 * [newId] produces a UUID version 7 (RFC 9562 §5.7):
 *   48-bit unix_ts_ms | 4-bit version=7 | 12-bit monotonic seq | 2-bit variant | 62-bit random
 *
 * Rationale (ADR-0106 Tier 1):
 * - UUIDv7 is time-ordered → B-tree index friendly (no page splits on insert).
 * - Hand-rolled implementation — no additional runtime dependency beyond the JDK.
 *   java.util.UUID uses `SecureRandom` internally too; we mirror that for the random half.
 * - Thread-safe via `@Synchronized` on the generation path; the lock is uncontended
 *   except within the same millisecond where the seq counter must be monotone.
 * - Framework-free: callable from the domain layer without any framework imports.
 */
object Ids {
    private val random = SecureRandom()
    private var lastMs = 0L
    private var seq = 0

    // UUIDv7 RFC 9562 §5.7 bit-field constants
    private const val SEQ_MASK = 0xFFF // 12-bit monotonic sequence mask
    private const val VERSION_BITS = 0x7000L // 4-bit version=7 field
    private const val RANDOM_MASK = 0x3FFFFFFFFFFFFFFFL // 62-bit random half mask
    private const val TS_SHIFT = 16 // ms occupies bits [127..80]; shift left 16 to make room for ver+seq

    /**
     * Returns a new, globally unique UUID version 7.
     *
     * The value is monotonically increasing within the same millisecond (up to 4096 IDs/ms)
     * and globally monotone across milliseconds, making it safe to sort by UUID value as a
     * proxy for creation time.
     */
    @Synchronized
    fun newId(): UUID {
        val ms = System.currentTimeMillis()
        if (ms == lastMs) {
            seq = (seq + 1) and SEQ_MASK
        } else {
            lastMs = ms
            seq = 0
        }
        // High 64 bits: [48-bit ms][4-bit ver=7][12-bit seq]
        val hi = (ms shl TS_SHIFT) or VERSION_BITS or seq.toLong()
        // Low 64 bits: [2-bit variant=10][62-bit random]
        val lo = (random.nextLong() and RANDOM_MASK) or Long.MIN_VALUE
        return UUID(hi, lo)
    }
}
