// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.audit

import com.openbank.customeredge.application.port.out.CustomerAuditEventPublisher
import jakarta.enterprise.context.ApplicationScoped

/**
 * Kafka adapter for [CustomerAuditEventPublisher] (ADR-0086).
 *
 * Delegates to [EdgeAuditPublisher], which owns the best-effort Kafka channel
 * ("customer-audit-out" → topic "openbank.customer.audit") and the catch-all
 * error handling that ensures audit never breaks the customer operation.
 *
 * The adapter translates the port's structured fields into the flat key-value
 * shape that [EdgeAuditPublisher.emit] produces (and that audit-service's
 * [AuditConsumer] parses). Field mapping:
 *  - actorPartyId → partyId + actorId
 *  - action       → operation
 *  - resourceType → injected into details as "resourceType"
 *  - resourceId   → resourceId
 *  - traceId      → details "traceId"
 *  - outcome      → result
 *  - payload      → details (merged)
 *
 * The eventType is derived from the action: "payments.domestic" → "CUSTOMER_ACTION"
 * (the audit-service consumer already handles CUSTOMER_ACTION via its aggregateType
 * extractor). The eventType prefix "CUSTOMER_" plus the action upper-snake makes
 * audit queries simpler than a flat "CUSTOMER_ACTION" for every event.
 */
@ApplicationScoped
class KafkaCustomerAuditEventPublisher(private val delegate: EdgeAuditPublisher) : CustomerAuditEventPublisher {

    override fun publish(
        actorPartyId: String,
        action: String,
        resourceType: String,
        resourceId: String?,
        traceId: String?,
        outcome: String,
        payload: Map<String, String?>,
    ) {
        val eventType = "CUSTOMER_${action.replace('.', '_').uppercase()}"
        val details = buildMap<String, String?> {
            put("resourceType", resourceType)
            traceId?.let { put("traceId", it) }
            putAll(payload)
        }
        delegate.emit(
            eventType = eventType,
            partyId = actorPartyId,
            operation = action,
            result = outcome,
            resourceId = resourceId,
            details = details,
        )
    }
}
