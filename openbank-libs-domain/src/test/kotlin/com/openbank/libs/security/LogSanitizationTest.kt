// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.kotest.property.Arb
import io.kotest.property.arbitrary.char
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LogSanitizationTest {

    @Test
    fun `null becomes the placeholder dash`() {
        val value: String? = null
        assertThat(value.sanitizeForLog()).isEqualTo("-")
    }

    @Test
    fun `blank string is left as-is, not replaced with the placeholder`() {
        assertThat("".sanitizeForLog()).isEqualTo("")
        assertThat("   ".sanitizeForLog()).isEqualTo("   ")
    }

    @Test
    fun `value with no CR or LF is unchanged`() {
        assertThat("perfectly-ordinary-value_123".sanitizeForLog()).isEqualTo("perfectly-ordinary-value_123")
    }

    @Test
    fun `a lone LF is replaced with an underscore`() {
        assertThat("line1\nline2".sanitizeForLog()).isEqualTo("line1_line2")
    }

    @Test
    fun `a CR LF pair becomes two underscores, one per character`() {
        // Matches the original per-file idiom's behavior exactly: .replace('\n','_').replace('\r','_')
        // replaces each control character independently, so CRLF is not collapsed to one underscore.
        assertThat("line1\r\nline2".sanitizeForLog()).isEqualTo("line1__line2")
    }

    @Test
    fun `a lone CR is replaced with an underscore`() {
        assertThat("line1\rline2".sanitizeForLog()).isEqualTo("line1_line2")
    }

    @Test
    fun `forged log line attempt is neutralised into a single line`() {
        val attack = "alice\nERROR: fake audit entry injected by attacker"
        val sanitized = attack.sanitizeForLog()
        assertThat(sanitized).doesNotContain("\n").doesNotContain("\r")
        assertThat(sanitized).isEqualTo("alice_ERROR: fake audit entry injected by attacker")
    }

    @Test
    fun `repeated CR LF pairs are each collapsed independently`() {
        assertThat("a\r\n\r\nb".sanitizeForLog()).isEqualTo("a____b")
    }

    // Every character a terminal, a log viewer or a line-oriented shipper may treat as a line
    // break or a control sequence: NEL (U+0085), LINE/PARAGRAPH SEPARATOR, ESC, the rest of C0,
    // DEL and C1.
    @ParameterizedTest
    @ValueSource(ints = [0x00, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x1B, 0x1F, 0x7F, 0x80, 0x85, 0x9B, 0x9F, 0x2028, 0x2029])
    fun `every control or line-separator character is replaced`(codePoint: Int) {
        val c = codePoint.toChar()
        assertThat("a${c}b".sanitizeForLog()).isEqualTo("a_b")
    }

    @Test
    fun `printable non-ASCII text is left intact`() {
        assertThat("Příliš žluťoučký kůň €".sanitizeForLog()).isEqualTo("Příliš žluťoučký kůň €")
    }

    @Test
    fun `logfmtValue leaves a plain token bare and renders null as the dash`() {
        assertThat("acc-1".logfmtValue()).isEqualTo("acc-1")
        assertThat("urn:x/y@z.1+2_3".logfmtValue()).isEqualTo("urn:x/y@z.1+2_3")
        assertThat((null as String?).logfmtValue()).isEqualTo("-")
    }

    @Test
    fun `logfmtValue quotes a literal dash and the empty string so neither reads as absent`() {
        assertThat("-".logfmtValue()).isEqualTo("\"-\"")
        assertThat("".logfmtValue()).isEqualTo("\"\"")
    }

    @Test
    fun `logfmtValue quotes spaces and equals signs so a value cannot add a field`() {
        assertThat("a result=SUCCESS".logfmtValue()).isEqualTo("\"a result=SUCCESS\"")
        assertThat("say \"hi\" \\ bye".logfmtValue()).isEqualTo("\"say \\\"hi\\\" \\\\ bye\"")
    }

    @Test
    fun `logfmtValue escapes line breaks and controls as unicode escapes`() {
        assertThat("a\nb\u2028c\u001bd\u0085e".logfmtValue())
            .isEqualTo("\"a\\u000ab\\u2028c\\u001bd\\u0085e\"")
    }

    private val hostile = Arb.choice(
        Arb.element('\n', '\r', '\u0085', '\u2028', '\u2029', '\u001b', '\u0000', '\t', '"', '\\', '=', ' ', '-'),
        Arb.char('\u0000'..'\u2fff'),
    )
    private val hostileString = Arb.list(hostile, 0..40).map { it.joinToString("") }

    @Test
    fun `sanitizeForLog output never contains a control or line-separator character`(): Unit = runBlocking {
        checkAll(1_000, hostileString) { v ->
            assertThat(v.sanitizeForLog().none { it.isISOControl() || it == '\u2028' || it == '\u2029' }).isTrue()
            assertThat(v.sanitizeForLog()).hasSameSizeAs(v)
        }
    }

    @Test
    fun `a line built from logfmtValue fields is one physical line with a fixed field count`(): Unit = runBlocking {
        checkAll(1_000, hostileString, hostileString, Arb.string(0..10)) { a, b, c ->
            val line = "x=${a.logfmtValue()} y=${b.logfmtValue()} z=${c.logfmtValue()}"
            assertThat(line.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }).isTrue()
            assertThat(parseLogfmt(line).keys).containsExactly("x", "y", "z")
            assertThat(parseLogfmt(line)["x"]).isEqualTo(a)
            assertThat(parseLogfmt(line)["y"]).isEqualTo(b)
        }
    }

    /** Minimal logfmt reader for the property above: bare tokens, or quoted with `\\`, `\"`, `\\uXXXX`. */
    private fun parseLogfmt(line: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < line.length) {
            val eq = line.indexOf('=', i)
            val key = line.substring(i, eq)
            val (value, next) = if (line[eq + 1] == '"') readQuoted(line, eq + 2) else readBare(line, eq + 1)
            out[key] = value
            i = next + 1 // the separating space
        }
        return out
    }

    private fun readBare(line: String, from: Int): Pair<String, Int> {
        val end = line.indexOf(' ', from).let { if (it < 0) line.length else it }
        return line.substring(from, end) to end
    }

    private fun readQuoted(line: String, from: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = from
        while (line[i] != '"') {
            if (line[i] != '\\') {
                sb.append(line[i++])
            } else if (line[i + 1] == 'u') {
                sb.append(line.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else {
                sb.append(line[i + 1])
                i += 2
            }
        }
        return sb.toString() to i + 1
    }
}
