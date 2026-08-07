// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.port.out

import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.SurfaceSlot
import java.time.Instant
import java.util.UUID

interface EngagementEventRepository {
    suspend fun save(event: EngagementEvent)

    /** Ordered oldest-first — the shape [com.openbank.engagement.domain.model.DismissalRule] needs. */
    suspend fun recentForPartyAndSlot(partyId: UUID, slot: SurfaceSlot, since: Instant): List<EngagementEvent>

    suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int
}

/** ADR-0200-style: consent stays the live per-call check against consent-service, never cached here. */
interface ConsentCheckPort {
    suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean
}
