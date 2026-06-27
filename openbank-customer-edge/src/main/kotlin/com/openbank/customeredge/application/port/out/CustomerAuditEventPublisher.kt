// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.application.port.out

/**
 * Output port: emit a tamper-evident audit event for a customer-initiated action (ADR-0086).
 *
 * The edge is the only place that still holds the real customer identity (the party JWT).
 * Upstream services operate on M2M tokens and cannot reconstruct the initiating party — making
 * this the canonical place to record *who* did *what* on *which* resource. Audit events are
 * consumed by audit-service, which hash-chains them for DORA Art. 17 incident response.
 *
 * Contract invariants:
 * - Emission MUST be fire-and-forget: it may never fail or delay the customer operation.
 *   A Kafka outage degrades to an ERROR log; it must not produce a 5xx to the caller.
 * - [actorPartyId] is the JWT-resolved party UUID (trusted, never client-supplied).
 * - [traceId] should be the W3C trace-id from the request context where available.
 * - [payload] is optional contextual JSON (amount, currency, creditor, …); PII-sensitive
 *   fields (national id, card PAN) must NEVER appear here — pass only what compliance
 *   already requires to appear in the regulated audit trail.
 */
interface CustomerAuditEventPublisher {

    /**
     * Emit a structured audit event for a customer-initiated action.
     *
     * @param actorPartyId  Party UUID of the authenticated customer (from JWT, never body).
     * @param action        Dot-notation action name, e.g. "payments.domestic", "sca.enrollDevice".
     * @param resourceType  The primary affected resource class, e.g. "PAYMENT", "ACCOUNT", "DEVICE".
     * @param resourceId    Upstream-assigned id of the created/affected resource; null for read-only.
     * @param traceId       W3C traceId from the request's OTel span (for correlation); null if unavailable.
     * @param outcome       "SUCCESS" or "FAILURE" (or "DENIED" for SCA-refused payments).
     * @param payload       Optional contextual key-value pairs (amount, currency, …). No PII, no secrets.
     */
    @Suppress("LongParameterList") // the audit contract deliberately requires all these
    fun publish(
        actorPartyId: String,
        action: String,
        resourceType: String,
        resourceId: String? = null,
        traceId: String? = null,
        outcome: String,
        payload: Map<String, String?> = emptyMap(),
    )
}
