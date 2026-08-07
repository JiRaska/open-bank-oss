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
 */
@ApplicationScoped
class RecordEngagementEventUseCase(private val events: EngagementEventRepository) {
    suspend fun record(event: EngagementEvent) {
        events.save(event)
    }
}
