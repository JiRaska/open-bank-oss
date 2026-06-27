// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.domain.event

import java.time.Instant
import java.util.UUID

data class StandingOrderCreated(
    val id: UUID,
    val partyId: UUID,
    val currency: String,
    val amountMinorUnits: Long,
    val at: Instant,
)
data class StandingOrderExecuted(val id: UUID, val partyId: UUID, val at: Instant)
data class StandingOrderCancelled(val id: UUID, val partyId: UUID, val at: Instant)
