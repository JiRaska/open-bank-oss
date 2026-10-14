// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

/**
 * A pension investment-strategy code as the edge is willing to put on an upstream URL.
 *
 * Not an enum on purpose: strategies are product-catalog data owned by pension-service (a new fund
 * strategy must not need an edge release), so the closed set the edge can own is the code's
 * ALPHABET, `[A-Z0-9_]{1,64}` — the same pattern the edge OpenAPI publishes. [parse] accepts a raw
 * caller value only if every character belongs to that alphabet and then rebuilds the code from the
 * edge's own [ALPHABET] constants, so [value] carries no caller-supplied character: what reaches the
 * URL is the canonical constant form, URL-safe by construction, never the request string itself.
 * Whether the code names a real strategy stays pension-service's answer.
 */
@JvmInline
value class PensionStrategyCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
        private const val MAX_LENGTH = 64

        /** The canonical code, or null when [raw] is absent, empty, too long or off-alphabet. */
        fun parse(raw: String?): PensionStrategyCode? {
            if (raw == null || raw.length !in 1..MAX_LENGTH) return null
            val canonical = CharArray(raw.length)
            for (i in raw.indices) {
                val index = ALPHABET.indexOf(raw[i])
                if (index < 0) return null
                canonical[i] = ALPHABET[index]
            }
            return PensionStrategyCode(String(canonical))
        }
    }
}
