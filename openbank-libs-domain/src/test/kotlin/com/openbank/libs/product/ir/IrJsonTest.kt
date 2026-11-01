// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class IrJsonTest {

    @Test
    fun `reads every JSON value kind into plain Kotlin values`() {
        val v = IrJson.parse(""" { "a": [1, -2.50, 3e2, true, false, null], "b": "x\n\"y\"é\/", "c": {} } """)
        assertThat(v).isEqualTo(
            linkedMapOf(
                "a" to listOf(BigDecimal("1"), BigDecimal("-2.50"), BigDecimal("3e2"), true, false, null),
                "b" to "x\n\"y\"é/",
                "c" to emptyMap<String, Any?>(),
            ),
        )
    }

    @Test
    fun `preserves the authored decimal text so scale is visible to the validator`() {
        assertThat((IrJson.parse("0.0250") as BigDecimal).scale()).isEqualTo(4)
        assertThat((IrJson.parse("0.025") as BigDecimal).scale()).isEqualTo(3)
    }

    @Test
    fun `rejects malformed documents with the offending offset`() {
        val bad = listOf(
            "{\"a\": 1, \"a\": 2}" to "duplicate key",
            "{\"a\": 1} x" to "trailing characters",
            "[1, 2" to "unterminated array",
            "{\"a\" 1}" to "expected ':'",
            "{1: 2}" to "expected a string key",
            "\"abc" to "unterminated string",
            "\"a\\qb\"" to "invalid escape",
            "\"a\\u12\"" to "truncated",
            "\"a\tb\"" to "control character",
            "01" to "trailing characters",
            "-" to "invalid number",
            "1." to "invalid number",
            "1e" to "invalid number",
            "tru" to "unexpected token",
            "@" to "unexpected character",
            "" to "unexpected end",
            "[1,]" to "unexpected character",
            "{,}" to "expected a string key",
            "{\"a\":1,}" to "expected a string key",
            "[1 2]" to "expected ',' or ']'",
            "{\"a\":1 \"b\":2}" to "expected ',' or '}'",
        )
        for ((doc, message) in bad) {
            assertThatThrownBy { IrJson.parse(doc) }
                .withFailMessage("expected <$doc> to be rejected with '$message'")
                .isInstanceOf(ProductIrValidationException::class.java)
                .hasMessageContaining(message)
        }
    }

    @Test
    fun `rejects nesting past the limit`() {
        val deep = "[".repeat(70) + "]".repeat(70)
        assertThatThrownBy { IrJson.parse(deep) }.hasMessageContaining("nesting deeper than")
    }

    @Test
    fun `decodes a well formed unicode escape`() {
        assertThat(IrJson.parse("\"\\u0041\"")).isEqualTo("A")
        assertThat(IrJson.parse("\"\\u00e9\\u00E9\"")).isEqualTo("éé")
    }

    @Test
    fun `rejects a plus signed unicode escape`() = assertBadUnicodeEscape("+041")

    @Test
    fun `rejects a minus signed unicode escape`() = assertBadUnicodeEscape("-001")

    @Test
    fun `rejects a non hex unicode escape`() = assertBadUnicodeEscape("00G1")

    private fun assertBadUnicodeEscape(hex: String) {
        val doc = "\"\\u$hex\""
        assertThatThrownBy { IrJson.parse(doc) }
            .withFailMessage("expected <$doc> to be rejected")
            .isInstanceOf(ProductIrValidationException::class.java)
            .hasMessageContaining("invalid \\u escape")
    }
}
