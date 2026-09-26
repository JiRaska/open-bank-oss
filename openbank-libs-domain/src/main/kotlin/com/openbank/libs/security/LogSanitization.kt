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
 * Deliberately implemented via [java.lang.String.replaceAll] rather than Kotlin's
 * `replace(Char, Char)` (which compiles to `kotlin.text.StringsKt.replace$default` and is opaque
 * to CodeQL's default Java/Kotlin sanitizer recognition) — `String.replaceAll` is a shape CodeQL's
 * standard library already has partial modeling for, which is a strictly better starting point for
 * a `.github/codeql/` barrier model than a Kotlin stdlib call, even though the model addition
 * itself still needs a real CodeQL run to confirm (not done in this change — no `codeql` CLI was
 * available to verify locally; see #6378 for the follow-up and #10907 for this migration).
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
    return (safe as java.lang.String).replaceAll("[\r\n]", "_")
}
