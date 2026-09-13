// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import java.time.Instant
import java.util.UUID

enum class ReservationProjectionApplyResult { APPLIED, STALE_OR_DUPLICATE }

interface DelegatedSpendBindingRepository {
    /** Apply one full snapshot; immutable-tuple contradictions fail instead of overwriting. */
    suspend fun applySnapshot(snapshot: DelegatedSpendReservationSnapshot): ReservationProjectionApplyResult

    /** Permanently close old PENDING rows and enqueue one outbox event per row, atomically. */
    suspend fun finalizeAbsentBefore(cutoff: Instant, limit: Int): Int

    suspend fun findByReservationId(reservationId: UUID): DelegatedSpendBinding?
}

class DelegatedSpendProjectionConflictException(message: String) : IllegalStateException(message)
