// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import java.math.BigDecimal

/**
 * A validation failure of an IR document. [path] is a JSON pointer-like location (`/interest/tiers/3`).
 * One exception carries every problem found, so an author fixes a document in one round.
 */
class ProductIrValidationException(val problems: List<Problem>) :
    IllegalArgumentException("invalid product IR: " + problems.joinToString("; ") { "${it.path}: ${it.message}" }) {
    data class Problem(val path: String, val message: String)
}

/**
 * Minimal strict JSON reader (RFC 8259) that yields plain Kotlin values: `Map<String, Any?>` with
 * insertion order, `List<Any?>`, `String`, `BigDecimal` for every number, `Boolean`, `null`.
 *
 * Pure Kotlin so `openbank-libs-domain` stays framework-free (ADR-0122; the domain-purity gate
 * treats Jackson as a framework). It is the reading half of [com.openbank.libs.audit.CanonicalJson].
 * Duplicate object keys are rejected rather than last-wins — a document whose meaning depends on
 * which parser read it has no canonical form.
 */
object IrJson {
    fun parse(text: String): Any? {
        val r = Reader(text)
        r.skipWs()
        val v = r.value(0)
        r.skipWs()
        if (!r.atEnd()) r.fail("trailing characters after the document")
        return v
    }

    private const val MAX_NESTING = 64

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(msg: String): Nothing =
            throw ProductIrValidationException(listOf(ProductIrValidationException.Problem("/", "$msg at offset $i")))

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_NESTING) fail("nesting deeper than $MAX_NESTING")
            if (atEnd()) fail("unexpected end of document")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected token")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("expected a string key")
                val key = string()
                skipWs()
                if (atEnd() || s[i] != ':') fail("expected ':'")
                i++
                skipWs()
                val v = value(depth + 1)
                if (out.containsKey(key)) fail("duplicate key \"$key\"")
                out[key] = v
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipWs()
                out.add(value(depth + 1))
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("truncated \\u escape")
                                val hex = s.substring(i, i + 4)
                                val cp = hex.toIntOrNull(16) ?: fail("invalid \\u escape")
                                sb.append(cp.toChar())
                                i += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): BigDecimal {
            val start = i
            if (s[i] == '-') i++
            if (atEnd() || s[i] !in '0'..'9') fail("invalid number")
            if (s[i] == '0') {
                i++
            } else {
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (atEnd() || s[i] !in '0'..'9') fail("invalid number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (atEnd() || s[i] !in '0'..'9') fail("invalid number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return BigDecimal(s.substring(start, i))
        }
    }
}
