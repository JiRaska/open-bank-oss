// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
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
 * Poison-pill safe: parse/handle failures are logged and swallowed so one bad event cannot wedge
 * the consumer group — lending-service's outbox remains the source of truth and can be replayed.
 */
@ApplicationScoped
class LendingArrearsEventConsumer(
    private val adverseState: AdverseStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(LendingArrearsEventConsumer::class.java)

    @Incoming("lending-events-in")
    @Suppress("TooGenericExceptionCaught") // poison-pill safety, same convention as
    // MarketingConsentEventConsumer/PartyEventConsumer across the fleet.
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("eventType") // topic carries only loan.stage_changed today, but check
                    .asText() != "loan.stage_changed"
            ) {
                return
            }
            val partyId = node.path("partyId").asText(null)?.let(UUID::fromString) ?: run {
                log.warnf("loan.stage_changed missing/unparseable partyId, skipping: %.300s", payload)
                return
            }
            val daysPastDue = node.path("daysPastDue").asInt(-1)
            if (daysPastDue < 0) {
                log.warnf("loan.stage_changed missing/unparseable daysPastDue, skipping: %.300s", payload)
                return
            }
            if (daysPastDue > 0) {
                adverseState.setActive(partyId, AdverseState.ARREARS, Instant.now())
            } else {
                adverseState.clearActive(partyId, AdverseState.ARREARS)
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to handle loan.stage_changed event: %.300s", payload)
        }
    }
}
