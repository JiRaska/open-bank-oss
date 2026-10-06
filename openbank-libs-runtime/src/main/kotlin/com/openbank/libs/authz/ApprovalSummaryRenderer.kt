// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

/**
 * Optional, service-supplied rendering of what a four-eyes approval binds, for the checker
 * (ADR-0155). Without a bean, the pending approval carries the generic summary — the endpoint and
 * its arguments with credential-like fields redacted. A service whose arguments carry material the
 * checker must not see verbatim (a public key, a creditor IBAN) or that needs a lookup to be
 * recognisable (which device a revocation targets) supplies one.
 *
 * Called whenever [AuthorizeInterceptor] binds a four-eyes request (a retry included), with the
 * same business arguments the request fingerprint covers; the result is stored only when a pending
 * approval is issued, so what the checker reads is the summary of what was bound, never re-derived. It is informational only: the fingerprint, not the
 * summary, decides whether a retry matches. The interceptor flattens control characters and caps
 * the result at [com.openbank.libs.approval.ApprovalRequestBinding.MAX_SUMMARY_LENGTH].
 *
 * Return `null` to keep the generic summary for an action the renderer does not cover. A thrown
 * exception refuses the call (503), as an unbindable argument does: an approval is never issued
 * with a summary the service could not produce.
 */
interface ApprovalSummaryRenderer {
    suspend fun render(action: String, resourceId: String?, arguments: Map<String, Any?>): String?
}
