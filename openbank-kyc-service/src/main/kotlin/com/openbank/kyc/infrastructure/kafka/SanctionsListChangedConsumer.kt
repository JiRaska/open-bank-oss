// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.ScreeningTriggerService
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Consumes `SANCTIONS_LIST_CHANGED` from `openbank.sanctions.screening.event` — the first
 * closed ADR-0256 trigger loop: sanctions-service's change journal (V15) publishes a diff event
 * per 64 changed/deactivated external ids; this consumer opens one screening case per candidate
 * party through the case coordinator (ADR-0256 contract), durably and idempotently.
 *
 * Event-type handling:
 * - `SANCTIONS_LIST_CHANGE_STORM` — the producer's storm guard (publication withheld). It must
 *   NOT open cases: the journal is retained upstream and a later publication re-fires the real
 *   diff. Logged at info and acked.
 * - `SANCTIONS_LIST_CHANGED` — handed to [ScreeningTriggerService.handleListChange].
 * - anything else on the topic — ignored.
 *
 * Failure handling follows the #5698 split (see PartyEventConsumer):
 * - a malformed payload is unretryable — logged and acked (the only poison-pill case);
 * - infrastructure failure (the trigger table, downstream reactive persistence) is retried with
 *   [EventRetry] and RETHROWN so the connector dead-letters — the channel wires
 *   failure-strategy dead-letter-queue with an explicit per-service topic;
 * - coordinator-level failure (429/503) is different from both: it is recorded durably
 *   (OPEN_FAILED trigger + KYC_COORDINATOR_OPEN_FAILED outbox row) and ACKED, because the retry
 *   job owns re-attempt. Rethrowing would double-book that role.
 *
 * The whole handler is gated on `openbank.kyc.screening-triggers.enabled` (default false): the
 * ADR-0256 contract's rollout sequence deploys the adapter disabled until the Pact verification,
 * sandbox coordinator exercise and operator-task metrics land.
 */
@ApplicationScoped
class SanctionsListChangedConsumer {

    @Inject
    lateinit var screeningTriggerService: ScreeningTriggerService

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.kyc.screening-triggers.enabled", defaultValue = "false")
    lateinit var enabled: String

    @ConfigProperty(name = "openbank.kyc.screening-triggers.candidate-limit", defaultValue = "500")
    lateinit var candidateLimit: String

    private val log = Logger.getLogger(SanctionsListChangedConsumer::class.java)

    @Incoming("sanctions-list-changes-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            // Malformed payload is the poison-pill case: replaying fails identically forever.
            log.errorf(e, "[sanctions-list-changes-in] undecodable event body, acking: %.300s", payload)
            return
        }

        when (node.path("eventType").asText()) {
            EVENT_STORM -> {
                // Deliberately NOT a case trigger: the guard means the diff was withheld as
                // suspicious; the retained journal republishes a real diff when it clears.
                log.infof(
                    "[sanctions-list-changes-in] SANCTIONS_LIST_CHANGE_STORM from %s (%s) — no cases opened",
                    node.path("listType").asText("?"),
                    node.path("reason").asText("?"),
                )
            }
            EVENT_CHANGED -> handleChanged(node)
            else -> Unit
        }
    }

    private suspend fun handleChanged(node: JsonNode) {
        if (!enabled.toBoolean()) {
            log.debugf("[sanctions-list-changes-in] ADR-0256 trigger loop disabled — acking")
            return
        }
        val publicationId = runCatching { UUID.fromString(node.path("publicationId").asText()) }.getOrNull()
        if (publicationId == null) {
            log.warnf(
                "[sanctions-list-changes-in] SANCTIONS_LIST_CHANGED without a valid publicationId, skipping: %.300s",
                node.toString(),
            )
            return
        }
        val change = ScreeningTriggerService.ListChange(
            publicationId = publicationId,
            chunkIndex = node.path("chunkIndex").asLong(0),
            listType = node.path("listType").asText(null),
            changedExternalIds = node.path("changedExternalIds").map { it.asText() },
            deactivatedExternalIds = node.path("deactivatedExternalIds").map { it.asText() },
        )
        // Retry-then-rethrow for INFRASTRUCTURE failure only; coordinator failure is durably
        // recorded inside the service and must not reach the DLQ (see class doc).
        val result = EventRetry.withRetry(log, "ADR-0256 sanctions-list-changed handling", publicationId) {
            screeningTriggerService.handleListChange(change, candidateLimit.toInt())
        }
        log.infof(
            "[sanctions-list-changes-in] publication %s chunk %d/%d: %d candidates, %d opened, %d failed, %d duplicates",
            publicationId,
            change.chunkIndex,
            node.path("chunkCount").asLong(-1),
            result.candidates,
            result.opened,
            result.failed,
            result.duplicates,
        )
    }

    private companion object {
        const val EVENT_CHANGED = "SANCTIONS_LIST_CHANGED"
        const val EVENT_STORM = "SANCTIONS_LIST_CHANGE_STORM"
    }
}
