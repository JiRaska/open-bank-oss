// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.util.UUID

/**
 * The card-clearing rail's invariant state: every presentment amount the scenario posted
 * against a REAL `CardAuthorization` through the REAL `AuthorizationLifecycle.clear` policy,
 * keyed by authorisation id, so the Layer-3 conservation invariant can replay
 * *Σ posted presentments == the authorisation's own `clearedAmountMinorUnits`* per
 * authorisation — a clearing that posted twice, or a presentment the aggregate lost, is caught
 * per-authorisation and cannot hide behind an unrelated posting.
 */
class CardClearingBook {
    private val posted = mutableMapOf<UUID, Long>()

    /** Record one presentment amount actually cleared against an authorisation. */
    fun recordPosted(authorizationId: UUID, amountMinorUnits: Long) {
        posted[authorizationId] = posted.getOrDefault(authorizationId, 0L) + amountMinorUnits
    }

    fun postedAmount(authorizationId: UUID): Long = posted.getOrDefault(authorizationId, 0L)

    fun postedIds(): Set<UUID> = posted.keys
}
