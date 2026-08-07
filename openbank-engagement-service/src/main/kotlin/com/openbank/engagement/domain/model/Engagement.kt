// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model

import java.time.Instant
import java.util.UUID

/** ADR-0220 D2: the four events the app posts back. */
enum class EngagementEventType { IMPRESSION, CLICK, DISMISS, CONVERSION }

/**
 * One posted engagement event (ADR-0220 D2). `conversion` here is deliberately the SAME concept
 * as ADR-0245's — a real observed product event, never a click proxy — so an `EngagementEventType`
 * carrying `CONVERSION` must be produced by the same discipline ADR-0245 D5 requires, not invented
 * by the app client. That wiring is infrastructure (follow-up PR); this domain layer only defines
 * the shape.
 */
data class EngagementEvent(
    val partyId: UUID,
    val contentId: String,
    val slot: SurfaceSlot,
    val type: EngagementEventType,
    val occurredAt: Instant,
)

/**
 * ADR-0220 D2: "Dismissal is a first-class negative signal: repeated dismissal of a content class
 * writes a topic-level suppression entry (ADR-0219 D3) rather than merely hiding one card."
 *
 * The ADR names the *rule* but not a threshold; [DISMISSALS_BEFORE_SUPPRESSION] is this slice's
 * own decision, not something quoted from the ADR — stated as a named constant, not a magic
 * number, precisely so a reviewer can see it is a choice and tune it. Three consecutive
 * dismissals with no intervening click or impression-then-ignore is the signal: a single dismiss
 * is often just bad timing, but three in a row with no engagement in between is a customer telling
 * the platform something.
 *
 * Writing the actual suppression entry into `ContactSuppressionPort` (ADR-0219 D3, already shipped
 * and reused by campaign-service) is infrastructure — this function only decides WHEN to.
 */
object DismissalRule {
    const val DISMISSALS_BEFORE_SUPPRESSION = 3

    /**
     * [recentEvents] must already be scoped to one party and one content class (e.g. one
     * `SurfaceSlot`), ordered oldest-first — this function does not do that scoping itself, so it
     * cannot silently mix signals across parties or slots.
     */
    fun shouldSuppress(recentEvents: List<EngagementEvent>): Boolean {
        var consecutiveDismissals = 0
        for (event in recentEvents) {
            consecutiveDismissals = when (event.type) {
                EngagementEventType.DISMISS -> consecutiveDismissals + 1
                EngagementEventType.CLICK, EngagementEventType.CONVERSION -> 0
                EngagementEventType.IMPRESSION -> consecutiveDismissals
            }
            if (consecutiveDismissals >= DISMISSALS_BEFORE_SUPPRESSION) return true
        }
        return false
    }
}
