// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.usecase

import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.application.port.out.LifecycleIdempotencyPort
import com.openbank.cardprocessing.application.port.out.LifecycleOperation
import com.openbank.cardprocessing.application.port.out.Reservation
import java.util.UUID

/**
 * In-memory [LifecycleIdempotencyPort] with the same semantics as the Postgres adapter: first reserve
 * wins, the same fingerprint replays or is in progress, a different one is a mismatch. Completion is
 * done by the test (the real adapter completes inside the repository's transaction).
 */
class FakeLifecycleIdempotency : LifecycleIdempotencyPort {
    private data class Row(val fingerprint: String, var resultId: UUID?)

    private val rows = mutableMapOf<String, Row>()
    val released = mutableListOf<IdempotencyClaim>()

    override suspend fun reserve(operation: LifecycleOperation, key: String, fingerprint: String): Reservation {
        val k = "$operation:$key"
        val row = rows[k] ?: run {
            rows[k] = Row(fingerprint, null)
            return Reservation.Claimed
        }
        return when {
            row.fingerprint != fingerprint -> Reservation.Mismatch
            row.resultId != null -> Reservation.Completed(row.resultId!!)
            else -> Reservation.InProgress
        }
    }

    override suspend fun release(claim: IdempotencyClaim) {
        val k = "${claim.operation}:${claim.key}"
        if (rows[k]?.resultId == null) rows.remove(k)
        released += claim
    }

    fun complete(claim: IdempotencyClaim, resultId: UUID) {
        rows.getValue("${claim.operation}:${claim.key}").resultId = resultId
    }

    fun isPending(operation: LifecycleOperation, key: String): Boolean =
        rows["$operation:$key"]?.let { it.resultId == null } ?: false
}
