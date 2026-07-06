// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.time.Instant
import java.util.UUID

/**
 * A session / access-log record (login, token refresh, logout, session-bound API access) —
 * behavioural PII under ADR-0118's PII classification table, distinct from the regulatory
 * `audit_entries` trail (10-year EBA/CNB retention, immutable by DB rule).
 *
 * Session logs have **no** statutory retention requirement (ADR-0118 §2: "Proportionality; no
 * specific statutory requirement") and are kept only 90 days, so they are stored in their own
 * mutable table (`session_logs`) rather than in `audit_entries` — the latter is hard-locked
 * against DELETE at the database level (`no_delete_audit` RULE, V1 migration) to satisfy the
 * 10-year AML/EBA retention obligation, and repurposing it for a 90-day window would violate
 * that obligation for every other row class it holds.
 */
data class SessionLogEntry(
    // ADR-0106: [id] is a durable, indexed identifier (unique-indexed log_id column), so it is
    // minted via Ids.newId() (time-ordered UUIDv7) rather than a bare JDK random UUID call.
    val id: UUID = Ids.newId(),
    val partyId: UUID?,
    val sessionId: String,
    val actorId: String?,
    val eventType: String,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val occurredAt: Instant,
)
