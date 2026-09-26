// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

/**
 * Neutralises CR/LF in a caller- or attacker-influenced value before it is written to a log line,
 * so it can't forge additional log entries (log forging / log injection, CWE-117).
 *
 * This was previously copy-pasted as a private per-file extension function in 14 services
 * (identical body: `(this ?: "-").replace('\n', '_').replace('\r', '_')`). Consolidated here so
 * there is exactly one implementation to keep in sync, and one call site a CodeQL sanitizer model
 * can be pointed at (#6378 — CodeQL's `java/log-injection` query does not recognise the
 * per-class idiom as a barrier, producing standing false positives on every already-sanitised
 * call site).
 *
 * Implemented as two chained `replace(Char, Char)` calls — identical output to the 14 copies it
 * replaces, with no per-call regex compilation and no `java.lang.String` platform-type cast. An
 * earlier draft of this migration used `(this as java.lang.String).replaceAll("[\r\n]", "_")` on
 * the theory that CodeQL's Java-side modeling for `String.replaceAll` would make a better starting
 * point for a `.github/codeql/` sanitizer-barrier model than a Kotlin stdlib call; that claim was
 * never verified (no `codeql` CLI was available to confirm it locally) and is not worth the regex
 * overhead and the platform-type cast on every call. Whether CodeQL's `java/log-injection` query
 * recognises this shared function as a barrier at all is still open and tracked in #6378 — fix
 * that gate by pointing a CodeQL sanitizer model at [sanitizeForLog] directly (a `neutralModel`/
 * `summaryModel` row keyed on this exact source location), not by choosing an implementation shape
 * to second-guess CodeQL's default recognition.
 *
 * Two fleet call sites intentionally do NOT use this shared function and are not migrated here,
 * because their behavior differs from simple CR/LF stripping:
 * - `openbank-libs-runtime` `HoneytokenFilter.sanitizeForLog` — an allow-list of
 *   `isLetterOrDigit()` plus `"/-_.:@[]"`, with truncation. Stricter than CR/LF stripping.
 * - `openbank-case-coordinator-agent` `CaseOpenService.sanitizeForLog` — same CR/LF stripping
 *   logic but declared on a non-nullable `String` receiver (no `?: "-"` fallback for null).
 */
fun String?.sanitizeForLog(): String {
    val safe = this ?: "-"
    return safe.replace('\n', '_').replace('\r', '_')
}
