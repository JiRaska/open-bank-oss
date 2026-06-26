// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.security

import jakarta.ws.rs.core.SecurityContext
import org.jboss.logging.MDC
import java.util.UUID

/**
 * Ergonomic accessors for the things every business endpoint needs from the security context.
 * All accessors are null-safe: they return `null` (or `"anonymous"`) rather than throw, since
 * exception mappers shouldn't need to special-case missing-principal NPEs.
 *
 * Used in conjunction with [com.openbank.libs.audit.AuditEvent] to stamp every mutation with
 * `actorId` / `actorType` for DORA + GDPR Art. 30 (Records of Processing).
 */

/** UUID of the authenticated principal, parsed from `sub` claim. Null if anonymous. */
val SecurityContext.currentUserId: UUID?
    get() = userPrincipal?.name?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Human-readable principal name. Falls back to `"anonymous"`. */
val SecurityContext.actorName: String
    get() = userPrincipal?.name ?: "anonymous"

/**
 * The single most-specific role held by the principal, e.g. `"ROLE_COMPLIANCE"`.
 * Used for audit `actorType` so audit logs distinguish operator-initiated vs service-initiated.
 *
 * Order matters: returns the most privileged role first (ADMIN > SUPERVISOR > COMPLIANCE > …).
 */
val SecurityContext.actorType: String
    get() = Roles.ALL.firstOrNull { isUserInRole(it) } ?: "anonymous"

/** Correlation ID for the current request, set by `CorrelationIdRequestFilter`. */
val correlationId: String
    get() = (MDC.get("correlationId") as? String) ?: "no-correlation-id"

/** Throws [SecurityException] if no role from [required] is present. */
fun SecurityContext.requireAnyRole(vararg required: String) {
    if (required.none { isUserInRole(it) }) {
        throw SecurityException("requires one of ${required.toList()}; principal has none")
    }
}
