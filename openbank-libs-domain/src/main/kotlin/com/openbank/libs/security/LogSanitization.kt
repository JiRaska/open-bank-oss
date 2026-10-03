// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

/**
 * Neutralises line breaks and control characters in a caller-supplied value before it is written
 * to a log line, so the value always stays on one line (CWE-117).
 *
 * Every ISO control character (C0, DEL, C1 — which covers ESC and NEL U+0085) and the Unicode
 * LINE SEPARATOR / PARAGRAPH SEPARATOR (U+2028/U+2029) is replaced one-for-one with `_`. CR/LF
 * alone was not enough: several log viewers and line-oriented shippers also break on NEL and
 * U+2028, and ESC starts a terminal control sequence. Printable non-ASCII text is untouched.
 *
 * This was previously copy-pasted as a private per-file extension function in 14 services
 * (identical body: `(this ?: "-").replace('\n', '_').replace('\r', '_')`). Consolidated here so
 * there is exactly one implementation to keep in sync, and one call site a CodeQL sanitizer model
 * can be pointed at (#6378 — CodeQL's `java/log-injection` query does not recognise the
 * per-class idiom as a barrier, producing standing false positives on every already-sanitised
 * call site).
 *
 * Whether CodeQL's `java/log-injection` query recognises this shared function as a barrier is
 * tracked in #6378 — fix that by pointing a CodeQL sanitizer model at [sanitizeForLog] directly,
 * not by choosing an implementation shape to second-guess CodeQL's default recognition.
 *
 * Two fleet call sites intentionally do NOT use this shared function and are not migrated here,
 * because their behavior differs from this one:
 * - `openbank-libs-runtime` `HoneytokenFilter.sanitizeForLog` — an allow-list of
 *   `isLetterOrDigit()` plus `"/-_.:@[]"`, with truncation. Stricter than this function.
 * - `openbank-case-coordinator-agent` `CaseOpenService.sanitizeForLog` — CR/LF-only stripping, declared on a
 *   non-nullable `String` receiver (no `?: "-"` fallback for null).
 */
fun String?.sanitizeForLog(): String {
    val safe = this ?: "-"
    if (safe.none(::isLogUnsafe)) return safe
    return buildString(safe.length) { safe.forEach { append(if (isLogUnsafe(it)) '_' else it) } }
}

private const val LINE_SEPARATOR = '\u2028'
private const val PARAGRAPH_SEPARATOR = '\u2029'

private fun isLogUnsafe(c: Char): Boolean = c.isISOControl() || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR

private val BARE_LOGFMT_TOKEN = Regex("[A-Za-z0-9._:/@+\\-]+")

/**
 * Renders a value for one `key=value` field of a log line so it can never add, split or rename
 * a field, and never leaves the line.
 *
 * - `null` → `-` (the absent-field placeholder).
 * - A non-empty token of `[A-Za-z0-9._:/@+-]` other than a lone `-` → unchanged, unquoted.
 * - Anything else → wrapped in double quotes, with `\` and `"` backslash-escaped and every
 *   control or line-separator character (see [sanitizeForLog]) written as `\uXXXX`.
 *
 * Unlike [sanitizeForLog] this is lossless: the original value can be recovered from the line.
 * A structured (JSON) log encoder protects the line as a whole, but the audit line's `key=value`
 * payload is still one `message` string, so its fields need this escaping regardless.
 */
fun String?.logfmtValue(): String {
    if (this == null) return "-"
    if (this != "-" && BARE_LOGFMT_TOKEN.matches(this)) return this
    return buildString(length + 2) {
        append('"')
        for (c in this@logfmtValue) {
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                isLogUnsafe(c) -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
