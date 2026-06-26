// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.customeredge.infrastructure.audit

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.OnOverflow
import java.time.Clock
import java.time.Instant

/**
 * Structured audit trail of CUSTOMER-INITIATED actions (GDPR Art. 30, DORA Art. 17).
 *
 * The edge is the only place that still knows the real customer identity (the party JWT) —
 * upstream services run on the M2M token, so without this record the initiator would be
 * unreconstructable from the operational stores. Every money-moving or identity-changing
 * route emits one event to `openbank.customer.audit`, which audit-service taps into its
 * hash-chained log alongside the domain-event topics.
 *
 * Best-effort BY DESIGN: an audit emission must never fail or delay the customer operation
 * (the operation's own durability lives in the domain services); a Kafka outage degrades to
 * an ERROR log, never a 5xx. The send is buffered (OnOverflow.BUFFER) so a broker hiccup
 * does not drop events that fit in memory.
 */
@ApplicationScoped
class EdgeAuditPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("customer-audit-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 1024)
    private val emitter: Emitter<Record<String, String>>,
    private val clock: Clock,
) {

    // catch-all IS the contract: audit never breaks the operation
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    fun emit(
        eventType: String,
        partyId: String,
        operation: String,
        result: String,
        resourceId: String? = null,
        details: Map<String, String?> = emptyMap(),
    ) {
        try {
            val node = objectMapper.createObjectNode()
            node.put("eventType", eventType)
            node.put("aggregateType", "CUSTOMER_ACTION")
            // partyId doubles as the aggregate id in audit-service's extractor — the audit
            // question is "what did THIS customer do", so the party is the aggregate.
            node.put("partyId", partyId)
            node.put("actorId", partyId)
            node.put("actorType", "CUSTOMER")
            node.put("operation", operation)
            node.put("result", result)
            resourceId?.let { node.put("resourceId", it) }
            node.put("sourceService", "customer-edge")
            node.put("occurredAt", Instant.now(clock).toString())
            details.forEach { (k, v) -> v?.let { node.put(k, it) } }
            emitter.send(Record.of(partyId, objectMapper.writeValueAsString(node)))
                .whenComplete { _, err ->
                    if (err != null) Log.error("audit emit failed for $operation/$partyId: ${err.message}")
                }
        } catch (e: Exception) {
            // Never let auditing break the customer operation — but make the gap loud.
            Log.error("audit emit failed for $operation/$partyId: ${e.message}", e)
        }
    }
}
