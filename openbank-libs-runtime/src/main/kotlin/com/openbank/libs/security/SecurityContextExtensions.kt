// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import jakarta.ws.rs.ForbiddenException
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
 * Returns the FIRST held role in [Roles.ALL] declaration order (ADMIN, OPERATOR, VIEWER,
 * COMPLIANCE, …). That order is not a privilege ranking — e.g. OPERATOR precedes SUPERVISOR —
 * and changing it would change the `actorType` of existing audit records, so it is fixed.
 */
val SecurityContext.actorType: String
    get() = Roles.ALL.firstOrNull { isUserInRole(it) } ?: "anonymous"

/** Correlation ID for the current request, set by `CorrelationIdRequestFilter`. */
val correlationId: String
    get() = (MDC.get("correlationId") as? String) ?: "no-correlation-id"

/**
 * Throws [ForbiddenException] (HTTP 403 via the shared mapper) if no role from [required] is
 * present. A plain `SecurityException` would reach the last-resort mapper as a 500.
 */
fun SecurityContext.requireAnyRole(vararg required: String) {
    if (required.none { isUserInRole(it) }) {
        throw ForbiddenException("requires one of ${required.toList()}; principal has none")
    }
}
