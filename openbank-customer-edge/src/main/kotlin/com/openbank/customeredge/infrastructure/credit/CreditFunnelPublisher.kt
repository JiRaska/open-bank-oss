// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.credit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.logging.Log
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.OnOverflow
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Credit-journey funnel telemetry (ADR-0269 rule 8's metrics).
 *
 * ## Why this is NOT the onboarding funnel channel
 *
 * The onboarding funnel is an ANONYMOUS, publicly postable stream: anyone can write to it, which is
 * exactly why its allow-lists are closed and its key is a client-generated session id. That design
 * is right for steps that happen before a session exists.
 *
 * Credit steps happen inside an authenticated session, and what they say is "this person is looking
 * at borrowing money". Posting that through an unauthenticated endpoint would be a leak channel
 * rather than instrumentation: an attacker could both inject junk about a party and — with a public
 * endpoint that accepted a party id — probe it. So this is a separate stream on a separate topic,
 * written only from an authenticated request, keyed by the party the JWT already proved.
 *
 * ## What it deliberately does not measure
 *
 * There is no conversion event here and no "offer accepted". The programme's metrics are
 * self-activation, opt-out-within-30-days, affordability declines, delinquency, and offers shown
 * without a prior customer action. A funnel built to optimise acceptance would quietly become the
 * thing ADR-0269 exists to prevent, so the vocabulary below simply does not contain the word.
 *
 * Best-effort, like every other telemetry path here: a Kafka outage degrades to an ERROR log, never
 * a 5xx. Telemetry must not break the screen it is watching.
 */
@ApplicationScoped
class CreditFunnelPublisher(
    private val objectMapper: ObjectMapper,
    @Channel("credit-funnel-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = BUFFER_SIZE)
    private val emitter: Emitter<Record<String, String>>,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {

    // catch-all IS the contract: funnel telemetry never breaks the customer's screen.
    @Suppress("TooGenericExceptionCaught")
    fun emit(partyId: UUID, step: String, action: String) {
        try {
            // Low-cardinality by construction: both values come from the closed allow-lists the
            // resource validates before this is reached, so no caller can inflate the label space.
            meterRegistry.counter("credit_funnel_events", "step", step, "action", action).increment()

            val payload = objectMapper.createObjectNode()
            payload.put("partyId", partyId.toString())
            payload.put("step", step)
            payload.put("action", action)

            val node = objectMapper.createObjectNode()
            // Bound to a local rather than passed straight in, because the ADR-0006
            // contract/code-agreement gate reads this source and cannot evaluate a constant: it
            // matches `eventType = "<literal>"`, and without that shape a documented message looks
            // to it like one nobody emits. EVENT_TYPE below stays as the published name.
            val eventType = "credit.funnel.step"
            node.put("eventType", eventType)
            node.put("aggregateType", AGGREGATE_TYPE)
            // Keyed by party, unlike onboarding: the session here IS the authenticated customer, and
            // the questions this answers ("did they come back after switching offers on") span
            // sessions. It is the same pseudonymous id the silver layer already holds — no PII.
            node.put("aggregateId", partyId.toString())
            node.put("sourceService", "customer-edge")
            node.put("schemaVersion", 1)
            node.put("occurredAt", Instant.now(clock).toString())
            node.set<ObjectNode>("payload", payload)

            emitter.send(Record.of(partyId.toString(), objectMapper.writeValueAsString(node)))
                .whenComplete { _, err ->
                    if (err != null) Log.error("credit funnel emit failed for $step/$action: ${err.message}")
                }
        } catch (e: Exception) {
            Log.error("credit funnel emit failed for $step/$action: ${e.message}", e)
        }
    }

    companion object {
        /** The contract message name (openbank-contracts/openbank-customer-edge/asyncapi.yaml). */
        const val EVENT_TYPE = "credit.funnel.step"
        const val AGGREGATE_TYPE = "CREDIT_FUNNEL"

        /**
         * Closed allow-lists. Same discipline as the onboarding funnel and for an additional reason
         * here: these values are labels on a Prometheus counter, so an open vocabulary would let a
         * client take the metrics store down.
         */
        val VALID_STEPS = setOf("CONSENT", "HEALTH", "FINANCING", "QUOTE", "APPLICATION")

        /**
         * Note what is absent: no CONVERTED, no ACCEPTED, no SOLD. The programme measures whether
         * customers switch offers on themselves and whether they switch them back off — not how
         * many of them borrowed. A vocabulary that could express conversion is a vocabulary someone
         * will eventually optimise.
         */
        val VALID_ACTIONS = setOf(
            "VIEWED",
            "CONSENT_GRANTED",
            "CONSENT_WITHDRAWN",
            "QUOTE_REQUESTED",
            "QUOTE_SUPPRESSED",
            "APPLICATION_STARTED",
            "APPLICATION_ABANDONED",
        )
    }
}

private const val BUFFER_SIZE = 2048L
