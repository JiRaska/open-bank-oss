// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import java.time.Instant
import java.util.UUID

/**
 * Canonical audit envelope. Every state-changing operation in OpenBank should emit one
 * of these into the audit pipeline (Kafka topic `audit-events-out`, consumed by
 * openbank-audit-service into the append-only audit store).
 *
 * Fields map to the GDPR Art. 30 Records of Processing requirements:
 *   - `actorId` / `actorType`        — who performed the operation
 *   - `operation` / `resourceType`   — purpose and category of processing
 *   - `resourceId`                   — the data subject identifier
 *   - `timestamp`                    — when
 *   - `ipAddress` / `userAgent`      — origin
 *   - `result`                       — outcome (SUCCESS / FAILURE / DENIED)
 *   - `payload`                      — sanitised before/after diff (NEVER raw PII;
 *                                       use [com.openbank.libs.security.PiiMask])
 *
 * DORA Art. 17 requires that incident-relevant operations be reconstructible from this
 * trail within 24h, so `traceId` ties the audit entry back to log lines for the same
 * request.
 *
 * ADR-0226 adds the cross-channel dimensions: one identity acts through several ingress
 * channels (admin UI, MCP, direct API), and the trail must answer "what did person X do"
 * in one query. `channel` names the ingress, `actChain` records the RFC 8693 on-behalf-of
 * delegation chain (ADR-0224) when the action was mediated, and `sessionId` groups one
 * sitting's events. All three are additive and nullable — producers adopt them channel by
 * channel, and an absent value means "unknown", never "ui".
 */
data class AuditEvent(
    val eventId: UUID = UUID.randomUUID(),
    val actorId: String,
    val actorType: String,
    val operation: String,
    val resourceType: String,
    val resourceId: String?,
    /**
     * When the audited operation happened. Defaults to construction time, which is what every
     * call site means — it previously defaulted to [Instant.EPOCH], and 23 of the 25 fleet
     * construction sites take the default, so the whole trail claimed 1970-01-01T00:00:00Z.
     * Pass an explicit value only when replaying or back-dating a historical operation.
     */
    val timestamp: Instant = Instant.now(),
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val result: AuditResult = AuditResult.SUCCESS,
    val traceId: String? = null,
    val channel: String? = null,
    val actChain: List<String> = emptyList(),
    val sessionId: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

enum class AuditResult { SUCCESS, FAILURE, DENIED }

/** Canonical values for [AuditEvent.channel] (ADR-0226 D1). */
object AuditChannel {
    /** Admin UI action relayed through the BFF proxy (ADR-0056). */
    const val UI = "ui"

    /** MCP tool call — AI agent or human operator via an MCP client (ADR-0181/0224). */
    const val MCP = "mcp"

    /** Direct REST call not relayed through the BFF (M2M, PSD2, service-to-service). */
    const val API = "api"
}

/*
 * There is deliberately no `@Audited` annotation (#4011). One existed and claimed in its own
 * KDoc that "the interceptor in each service picks this up" — no such interceptor was ever
 * written, in libs or in any service, and nothing ever applied it. A method carrying it
 * emitted no audit event while the source read as audited, and a threat model cited it as the
 * Repudiation mitigation for operator reads. Emit an [AuditEvent] through [AuditEventPublisher]
 * explicitly; a declarative form must arrive with its interceptor in the same change.
 */
