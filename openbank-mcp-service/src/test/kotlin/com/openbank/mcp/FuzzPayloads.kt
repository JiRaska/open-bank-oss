// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import java.util.Locale
import java.util.Random

/**
 * Deterministic payload corpus for the `tools/call` fuzz layer (issue #16617, layer 5), shared by
 * the in-process sweep ([McpToolCallFuzzTest]) and the wire-level sweep ([McpToolCallWireFuzzIT]).
 *
 * **Why hand-rolled and not jqwik.** jqwik is not a dependency anywhere in this repository, and the
 * decisive property here is not "many random inputs" but "this exact hostile shape was tried": NUL,
 * lone surrogates, a megabyte, nesting past the parser limit. A fixed corpus names each of those
 * and replays identically on every run, which a seeded generator cannot promise across library
 * upgrades; [randomised] adds seeded variety on top without a new dependency (licence, maintenance
 * and CVE surface all avoided).
 *
 * Every payload is RAW JSON TEXT, not a tree, on purpose. A lone surrogate or a NUL exists on the
 * wire only in its escaped form (`\ud800`, `\u0000`): building it as a Kotlin string and letting a
 * serializer write it would silently replace it, and the test would exercise a different input
 * from the one the label claims.
 */
object FuzzPayloads {

    /** One hostile JSON value. [json] is raw JSON text; the flags drive per-shape invariants. */
    data class Payload(val label: String, val json: String, val containsNul: Boolean = false) {
        val isString: Boolean get() = json.startsWith("\"")
        val isHuge: Boolean get() = json.length > HUGE_THRESHOLD
        val isUnparseableDeep: Boolean get() = label.startsWith("deep-") && label.endsWith("-over-limit")
        override fun toString() = label
    }

    private const val HUGE_THRESHOLD = 500_000
    const val MEGABYTE = 1_048_576

    /** Jackson's default StreamReadConstraints nesting limit is 1000; stay clearly on each side. */
    private const val NEST_UNDER = 900
    private const val NEST_OVER = 5_000

    private fun str(label: String, body: String, nul: Boolean = false) = Payload(label, "\"$body\"", nul)

    val ibanShapedGarbage: List<Payload> = listOf(
        str("iban-wrong-checksum", "CZ0000000000000000000000"),
        str("iban-lowercase-spaces", "cz65 0800 0000 1920 0014 5398"),
        str("iban-country-only", "CZ"),
        str("iban-too-long", "CZ65" + "9".repeat(10_000)),
        str("iban-with-nul", "CZ6508000000192000\\u00005399", nul = true),
        str("iban-sql-tail", "CZ6508000000192000145399'; DROP TABLE accounts;--"),
        str("iban-path-traversal", "../../../etc/passwd"),
        str("iban-url-encoded-nul", "CZ65%00"),
        str("iban-template-injection", "\${jndi:ldap://127.0.0.1/a}"),
        str("iban-homoglyph", "CZ65\\u0430800000019200014539"),
        str("iban-fullwidth-digits", "CZ\\uff16\\uff15" + "0".repeat(20)),
        str("iban-bidi-override", "CZ65\\u202e0800000019200014539"),
        str("uuid-last-char-invalid", "00000000-0000-0000-0000-00000000000g"),
    )

    val strings: List<Payload> = listOf(
        str("empty", ""),
        str("space", " "),
        str("nul", "\\u0000", nul = true),
        str("nul-in-middle", "abc\\u0000def", nul = true),
        str("lone-high-surrogate", "\\ud800"),
        str("lone-low-surrogate", "\\udc00"),
        str("two-high-surrogates", "\\ud800\\ud800"),
        str("reversed-surrogate-pair", "\\udc00\\ud800"),
        str("noncharacter-ffff", "\\uffff"),
        str("bom", "\\ufeff"),
        str("control-characters", "\\u0001\\u001f\\n\\r\\t\\u007f"),
        str("emoji-astral", "\\ud83d\\ude00"),
        str("one-megabyte-ascii", "A".repeat(MEGABYTE)),
        str("one-megabyte-multibyte", "\\u00e9".repeat(MEGABYTE / 6)),
        str("combining-marks-64k", "e" + "\\u0301".repeat(64 * 1024 / 6)),
    ) + ibanShapedGarbage

    val numbers: List<Payload> = listOf(
        "0", "1", "-1", "-0", "-0.0", "0.5", "2147483647", "2147483648", "4294967296", "-2147483649",
        "9223372036854775807", "9223372036854775808", "-9223372036854775809", "1e308", "-1e308", "1e400",
        "1e-400", "1.0000000000000000001", "1" + "0".repeat(500),
    ).map { Payload("number-$it".take(40), it) }

    val otherTypes: List<Payload> = listOf(
        Payload("null", "null"),
        Payload("true", "true"),
        Payload("false", "false"),
        Payload("empty-array", "[]"),
        Payload("mixed-array", "[1,\"a\",null,{}]"),
        Payload("empty-object", "{}"),
        Payload("nested-object", """{"a":{"b":[1,2,{"c":null}]}}"""),
        Payload("string-that-looks-like-json", "\"{\\\"accountId\\\":\\\"x\\\"}\""),
        Payload("deep-array-$NEST_UNDER", "[".repeat(NEST_UNDER) + "]".repeat(NEST_UNDER)),
        Payload("deep-object-$NEST_UNDER", "{\"a\":".repeat(NEST_UNDER) + "1" + "}".repeat(NEST_UNDER)),
        Payload("deep-array-$NEST_OVER-over-limit", "[".repeat(NEST_OVER) + "]".repeat(NEST_OVER)),
        Payload("deep-object-$NEST_OVER-over-limit", "{\"a\":".repeat(NEST_OVER) + "1" + "}".repeat(NEST_OVER)),
    )

    val all: List<Payload> = strings + numbers + otherTypes

    /** Payloads small enough to send in bulk over the wire. */
    val small: List<Payload> = all.filterNot { it.isHuge }

    /** Seeded extra variety: random printable/astral/control mixes of random length. */
    fun randomised(seed: Long, count: Int): List<Payload> {
        val rnd = Random(seed)
        return (0 until count).map { i ->
            val len = rnd.nextInt(256)
            val sb = StringBuilder()
            repeat(len) {
                when (rnd.nextInt(6)) {
                    0 -> sb.append("\\u00").append(String.format(Locale.ROOT, "%02x", rnd.nextInt(0x20)))
                    1 -> sb.append("\\ud83d\\ude00")
                    2 -> sb.append("\\u").append(String.format(Locale.ROOT, "%04x", 0xd800 + rnd.nextInt(0x800)))
                    else -> sb.append(('a'.code + rnd.nextInt(26)).toChar())
                }
            }
            val s = sb.toString()
            Payload("random-$seed-$i", "\"$s\"", containsNul = s.contains("\\u0000"))
        }
    }
}
