// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.EngagementEventRepository
import com.openbank.engagement.domain.model.EngagementEvent
import jakarta.enterprise.context.ApplicationScoped

/**
 * ADR-0220 D2: persist the posted event (the outbox write happens in the same transaction, in
 * the repository implementation — see `EngagementEventRepositoryImpl`). Publishing to
 * `engagement.events` is the outbox dispatcher's job, not this use case's.
 *
 * Deliberately does NOT evaluate [com.openbank.engagement.domain.model.DismissalRule] here.
 * `ContactSuppressionPort` (`openbank-libs-runtime`, ADR-0219 D3) is read-only by contract
 * (`activeSuppressions`, no write method) and its `SuppressionReason` vocabulary is a closed enum
 * with no value that honestly describes "repeated dismissal" — `CUSTOMER_OPTOUT`, `COMPLAINT`,
 * `RM_MANAGED`, `LEGAL_HOLD`, `DECEASED`. Extending that shared enum is a fleet-wide contract
 * change this slice does not make. [ResolveSurfaceUseCase] instead evaluates the rule directly
 * against this repository's own event history at read time — a local, engagement-specific
 * exclusion, not routed through the shared suppression list.
 *
 * ADR-0220 D3 (gamification, this slice): after the event is durably persisted, hand its own
 * generated id to [AwardGamificationPointsUseCase] as the correlation id for any
 * [com.openbank.engagement.domain.model.gamification.GamificationAward] it triggers. Deliberately
 * sequenced AFTER `events.save` returns — an award must never correlate to an event that was not
 * itself durably committed.
 */
@ApplicationScoped
class RecordEngagementEventUseCase(
    private val events: EngagementEventRepository,
    private val awardGamificationPoints: AwardGamificationPointsUseCase,
) {
    suspend fun record(event: EngagementEvent) {
        val eventId = events.save(event)
        awardGamificationPoints.evaluate(event, correlationEventId = eventId)
    }
}
