// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.logging.Log
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.OnOverflow
import java.time.Clock
import java.time.Instant

/**
 * Business onboarding-funnel telemetry (ADR-0069 Phase 2, funnel analytics).
 *
 * The retail app posts one event per meaningful onboarding transition — a step being viewed or
 * completed, a hold-to-confirm being started or abandoned, a KYC method chosen, a signature
 * attempt/success/failure. This publisher normalises each into the same [AnalyticsEnvelope]-shaped
 * JSON the analytics-sink already ingests (eventType / aggregateType / aggregateId / occurredAt /
 * sourceService / payload), and emits it to `openbank.onboarding.funnel.events`, from which
 * analytics-sink lands it in the ClickHouse bronze layer for the admin cockpit's conversion board.
 *
 * WHY a dedicated funnel stream and not RUM: the first onboarding steps (welcome, identity, email,
 * consent) happen BEFORE any Keycloak session exists, so RUM's OIDC-gated ingest cannot see exactly
 * the drop-off we care about; RUM is also consent-gated (biased sample) and lands in Tempo/Prometheus,
 * not an event store you can run conversion SQL over. This stream is pseudonymous (a client-generated
 * onboarding session id, never PII), best-effort, and legitimate-interest operational telemetry.
 *
 * Best-effort BY DESIGN, mirroring [com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher]:
 * a telemetry emission must never fail or delay the customer's onboarding. A Kafka outage degrades to
 * an ERROR log, never a 5xx; the send is buffered so a broker hiccup does not drop in-memory events.
 *
 * The `aggregateId` is the onboarding session id so every event of one attempt collapses onto one
 * funnel row in ClickHouse; `partyId` (known only from the sign step onward) rides in the payload so a
 * completed session can be joined to its party without ever keying the stream on identity.
 */
@ApplicationScoped
class OnboardingFunnelPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("onboarding-funnel-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 2048)
    private val emitter: Emitter<Record<String, String>>,
    private val clock: Clock,
    // Prometheus-facing counters (auto-exposed via quarkus-micrometer-registry-prometheus). These
    // let alerting reason about the funnel in real time — e.g. the SIGN_SUCCESS/SIGN_ATTEMPT ratio
    // — WITHOUT waiting on the ClickHouse pipeline (the warehouse is for exploration, not paging).
    private val meterRegistry: MeterRegistry,
) {

    // catch-all IS the contract: funnel telemetry never breaks onboarding.
    @Suppress("TooGenericExceptionCaught")
    fun emit(sessionId: String, step: String, action: String, attributes: Map<String, String?> = emptyMap()) {
        try {
            // Low-cardinality counter (step & action are closed allow-lists, validated by the
            // resource before we get here) so an alert can watch the sign conversion rate live.
            // Base name has NO _total suffix — the Prometheus registry appends it, so this is
            // scraped as `onboarding_funnel_events_total{step,action}`.
            meterRegistry.counter("onboarding_funnel_events", "step", step, "action", action)
                .increment()
            val payload = objectMapper.createObjectNode()
            payload.put("sessionId", sessionId)
            payload.put("step", step)
            payload.put("action", action)
            attributes.forEach { (k, v) -> v?.let { payload.put(k, it) } }

            val node = objectMapper.createObjectNode()
            // Bound to a local rather than passed straight in, because the ADR-0006
            // contract/code-agreement gate reads this source and cannot evaluate a constant: it
            // matches `eventType = "<literal>"`, and without that shape a documented message looks
            // to it like one nobody emits. EVENT_TYPE below stays as the published name.
            val eventType = "onboarding.funnel.step"
            node.put("eventType", eventType)
            node.put("aggregateType", AGGREGATE_TYPE)
            // The session id is the aggregate: every event of one onboarding attempt shares it, so the
            // warehouse funnel groups an attempt without ever keying on the (later) party identity.
            node.put("aggregateId", sessionId)
            node.put("sourceService", "customer-edge")
            node.put("schemaVersion", 1)
            node.put("occurredAt", Instant.now(clock).toString())
            node.set<com.fasterxml.jackson.databind.node.ObjectNode>("payload", payload)

            emitter.send(Record.of(sessionId, objectMapper.writeValueAsString(node)))
                .whenComplete { _, err ->
                    if (err != null) Log.error("funnel emit failed for $step/$action: ${err.message}")
                }
        } catch (e: Exception) {
            // Never let telemetry break onboarding — but make the gap loud.
            Log.error("funnel emit failed for $step/$action: ${e.message}", e)
        }
    }

    companion object {
        /** The contract message name (openbank-contracts/openbank-customer-edge/asyncapi.yaml). */
        const val EVENT_TYPE = "onboarding.funnel.step"
        const val AGGREGATE_TYPE = "ONBOARDING_FUNNEL"

        // Closed allow-lists: this is an ANONYMOUS write into a 10-year store, so the resource rejects
        // anything not enumerated here rather than let an abuser inflate cardinality with junk values.
        val VALID_STEPS = setOf("WELCOME", "IDENTITY", "EMAIL", "AGREEMENT", "PASSKEY", "SIGN")
        val VALID_ACTIONS = setOf(
            "STEP_VIEWED",
            "STEP_COMPLETED",
            "HOLD_STARTED",
            "HOLD_ABANDONED",
            "KYC_METHOD_CHOSEN",
            "SIGN_ATTEMPT",
            "SIGN_SUCCESS",
            "SIGN_FAIL",
        )
    }
}
