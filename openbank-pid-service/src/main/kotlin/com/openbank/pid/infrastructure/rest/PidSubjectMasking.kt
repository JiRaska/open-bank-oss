// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

private const val MASK_VISIBLE_TAIL = 4

/**
 * Mask a government PID subject identifier for API responses and audit logs: keep an optional
 * issuer-namespace prefix (the part before the first `:`), then reveal the last
 * [MASK_VISIBLE_TAIL] characters of the remainder only when the remainder is longer than that —
 * never the whole identifier.
 *
 * Consolidates `EudiDtos.kt#maskSubjectId` (used for `PidClaimsResponse.subjectIdMasked`) and
 * `EudiCredentialIssuerResource.kt#maskSubject` (used in an audit log line) into one function
 * (openbank-pid-service, issue #11027 follow-up — the two were near-duplicates, not identical).
 * A table-driven comparison of the two originals (`PidSubjectMaskingDedupTest`) found they
 * disagreed on short identifiers: the DTO version echoed the WHOLE subject verbatim whenever it
 * was [MASK_VISIBLE_TAIL] characters or shorter, or contained no `:` separator at all — that is a
 * leak, not a mask, for exactly the shortest and therefore highest-risk inputs. The log-line
 * version already handled that case safely by fully redacting to `"***"` instead. This function
 * takes that stricter, less-revealing behaviour for BOTH call sites; the namespace prefix itself
 * carries no personal data (it identifies the issuing scheme, e.g. an EUDI wallet provider) and is
 * kept because `PidClaimsResponse.subjectIdMasked` already returns a plain `string` with no format
 * constraint in `openapi.yaml` (no test, pact or contract pins the old shape), so changing the
 * short-input case does not break any published contract.
 */
internal fun maskPidSubject(subjectId: String): String {
    if (subjectId.isEmpty()) return "***"
    val prefix = subjectId.substringBefore(":", missingDelimiterValue = "")
    val remainder = if (prefix.isNotEmpty()) subjectId.substring(prefix.length + 1) else subjectId
    val maskedRemainder = if (remainder.length > MASK_VISIBLE_TAIL) {
        "***${remainder.takeLast(MASK_VISIBLE_TAIL)}"
    } else {
        "***"
    }
    return if (prefix.isNotEmpty()) "$prefix:$maskedRemainder" else maskedRemainder
}
