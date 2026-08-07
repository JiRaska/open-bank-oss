// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.contact

import java.util.UUID

/**
 * ADR-0219 D1's deterministic arbitration: when several items are simultaneously eligible for one
 * slot or one cap unit, the winner is a reviewed decision, not an accident of iteration order —
 * SERVICE_EXEMPT first, then a non-expired standing decision (ADR-0220 D4), then NBA-ranked
 * content, then plain campaign steps; ties broken by priority, then id.
 */
object ContactArbitration {

    /** The content tier an eligible item belongs to, in winning order. */
    enum class Tier { SERVICE_EXEMPT, STANDING_DECISION, NBA_RANKED, CAMPAIGN_STEP }

    /** One eligible item: [priority] wins inside a tier (higher first), [id] breaks final ties. */
    data class Eligible<T>(val tier: Tier, val priority: Int, val id: UUID, val payload: T)

    /** The single winner, or null when nothing is eligible. Deterministic for equal inputs. */
    fun <T> chooseWinner(items: List<Eligible<T>>): Eligible<T>? = items.minWithOrNull(
        compareBy<Eligible<T>> { it.tier.ordinal }
            .thenByDescending { it.priority }
            .thenBy { it.id },
    )
}
