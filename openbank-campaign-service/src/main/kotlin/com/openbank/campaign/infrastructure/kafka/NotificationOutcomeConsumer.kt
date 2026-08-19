// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * ADR-0239 D3: consumes `openbank.notification.outcomes.v1` and settles the send log's
 * `deliveryStatus`, so the campaign console stops reporting an accepted handoff as a delivered
 * message (issue #3663).
 *
 * The topic is SHARED — notification-service emits an outcome for every terminal transition, from
 * every producer. Most records this channel sees therefore belong to somebody else and correlate
 * with nothing here. That is the normal case, not an error, and it is why an unmatched
 * `correlationId` is dropped quietly rather than logged per record: logging it would produce one
 * warning per unrelated notification in the estate.
 */
@ApplicationScoped
class NotificationOutcomeConsumer(private val sendLog: SendLogRepository, private val mapper: ObjectMapper) {

    private val log = Logger.getLogger(NotificationOutcomeConsumer::class.java)

    /**
     * `suspend`, NOT a plain method wrapping `runBlocking` — the same constraint, and the same
     * reason, as [ConsentEventConsumer]: SmallRye invokes a plain `@Incoming` method on a Kafka
     * consumer thread that carries no Vert.x context, so the first reactive Panache call throws and
     * the message is nacked fail-stop, taking the whole channel down with it.
     *
     * **An unparseable record is still dropped; a failing repository is not.** The old KDoc argued
     * that leaving a row `PENDING` was "bounded and visible" because `PENDING` already means "no
     * outcome arrived". It is not visible: `PENDING` is exactly what the console shows for a send
     * whose outcome is merely in flight, so a lost outcome is indistinguishable from a pending one
     * and the row stays that way forever — which is precisely the ADR-0239 D3 / #3663 defect this
     * consumer was built to close. The consumer reintroduced it through the ack.
     *
     * So a write failure is retried a bounded number of times by [EventRetry] and then RETHROWN.
     * A `suspend @Incoming` method that throws is nacked by the connector.
     *
     * **What that does on `notification-outcomes-in` today: it stops the channel.** No
     * `failure-strategy` is configured here, so SmallRye's default `fail` applies and there is no
     * dead-letter topic to land in. That is the deliberate trade — a halted channel is loud and its
     * backlog survives, where the ack was silent and the outcome did not — but nothing below
     * dead-letters, and this KDoc will not say it does. Wiring the DLQ is #5745 section B.
     */
    @Suppress("TooGenericExceptionCaught")
    @Incoming("notification-outcomes-in")
    suspend fun onOutcome(payload: String) {
        val event = try {
            mapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable notification outcome — dropped")
            return
        }
        // Absent (an uncorrelated producer) or unparseable: nothing to match, and no need to say so.
        val node = event.path("correlationId")
        if (node.isMissingNode || node.isNull) return
        val sendId = runCatching { UUID.fromString(node.asText()) }.getOrNull() ?: return
        val outcome = event.path("outcome").asText().takeIf { it.isNotBlank() } ?: return
        val reason = event.path("reason").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        // The producer's clock, not ours: this is when the transition happened, and a consumer
        // stamping its own receipt time would make the column mean "when we heard" instead.
        // Falls back to now only when the field is unusable, which the contract says cannot happen.
        val occurredAt = runCatching { Instant.parse(event.path("occurredAt").asText()) }
            .getOrElse { Instant.now() }

        val moved = EventRetry.withRetry(log, "Delivery outcome", sendId) {
            sendLog.applyDeliveryOutcome(sendId, outcome, reason, occurredAt)
        }
        if (moved) {
            log.infof("Send %s delivery outcome=%s reason=%s", sendId, outcome, reason ?: "-")
        }
    }
}
