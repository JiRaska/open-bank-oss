// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Consumes `openbank-lending-service`'s `loan.stage_changed` and materialises the
 * [AdverseState.ARREARS] half of ADR-0220 D3.5's targeting exclusion (`ResolveSurfaceUseCase`
 * previously always constructed an empty adverse-state set — see its own comment). `partyId` was
 * added to this event's payload for exactly this consumer (`openbank-lending-service`'s
 * `LendingService.kt`) — an additive field, harmless to the existing `LoanStageEventConsumer` in
 * anacredit-service, which reads the same topic via `ObjectMapper.readTree` and ignores unknown
 * fields.
 *
 * `daysPastDue > 0` sets ARREARS; `daysPastDue == 0` on a later transition clears it — this
 * consumer only sees a message on a genuine IFRS 9 stage transition (the emit guard in
 * `LendingService.kt` is unconditional on `prior.stage != snapshot.stage`), so the state is a
 * coarse "as of the last stage change" signal, not a continuously fresh DPD feed. Good enough for
 * a targeting exclusion; do not read it as a live delinquency metric.
 *
 * Poison-pill safe for a MALFORMED event: an unparseable payload, a missing partyId or a missing
 * daysPastDue is logged and acked, because replaying it fails identically forever. A failure of the
 * `adverseState` write is the opposite case and is retried, then rethrown for the DLQ — see
 * [withBoundedRetry] (#5698).
 */
@ApplicationScoped
class LendingArrearsEventConsumer(
    private val adverseState: AdverseStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(LendingArrearsEventConsumer::class.java)

    @Incoming("lending-events-in")
    suspend fun consume(payload: String) {
        val signal = parse(payload) ?: return
        EventRetry.withRetry(log, "arrears exclusion for party ${signal.partyId} (dpd=${signal.daysPastDue})", null) {
            if (signal.daysPastDue > 0) {
                adverseState.setActive(signal.partyId, AdverseState.ARREARS, Instant.now())
            } else {
                adverseState.clearActive(signal.partyId, AdverseState.ARREARS)
            }
        }
    }

    /** Parsing + routing only — every outcome here is unretryable, so a null means "ack and move on". */
    // TooGenericExceptionCaught: whatever Jackson or UUID.fromString throws, a malformed payload is
    // the same unretryable poison pill. ReturnCount: one guard per required field reads far better
    // than folding four conditions together.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun parse(payload: String): ArrearsSignal? {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Failed to parse loan.stage_changed event: %.300s", payload)
            return null
        }
        // topic carries only loan.stage_changed today, but check
        if (node.path("eventType").asText() != "loan.stage_changed") return null

        val partyId = runCatching { node.path("partyId").asText(null)?.let(UUID::fromString) }.getOrNull()
        if (partyId == null) {
            log.warnf("loan.stage_changed missing/unparseable partyId, skipping: %.300s", payload)
            return null
        }
        val daysPastDue = node.path("daysPastDue").asInt(-1)
        if (daysPastDue < 0) {
            log.warnf("loan.stage_changed missing/unparseable daysPastDue, skipping: %.300s", payload)
            return null
        }
        return ArrearsSignal(partyId, daysPastDue)
    }

    private data class ArrearsSignal(val partyId: UUID, val daysPastDue: Int)
}
