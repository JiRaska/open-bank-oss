// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.application.port.out

import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SettlementPosition
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.util.UUID

/** Outbound persistence port for clearing batches (settlement cycles per rail). */
interface ClearingBatchRepository {

    fun save(batch: ClearingBatch): Uni<ClearingBatch>

    fun findById(id: UUID): Uni<ClearingBatch?>

    fun findByStatus(status: ClearingStatus): Uni<List<ClearingBatch>>

    fun findAll(page: Int, size: Int): Uni<List<ClearingBatch>>

    fun update(batch: ClearingBatch): Uni<ClearingBatch>
}

/** Outbound persistence port for individual clearing items (payments in a batch). */
interface ClearingItemRepository {

    fun save(item: ClearingItem): Uni<ClearingItem>

    fun saveAll(items: List<ClearingItem>): Uni<List<ClearingItem>>

    fun findById(id: UUID): Uni<ClearingItem?>

    fun findByBatchId(batchId: UUID): Uni<List<ClearingItem>>

    fun findByPaymentId(paymentId: UUID): Uni<List<ClearingItem>>

    fun findPendingByRail(rail: PaymentRail, limit: Int): Uni<List<ClearingItem>>

    fun updateStatus(id: UUID, status: ClearingStatus, errorCode: String?, errorMessage: String?): Uni<Int>
}

/** Outbound persistence port for per-participant net settlement positions. */
interface SettlementPositionRepository {

    fun save(position: SettlementPosition): Uni<SettlementPosition>

    fun findByCycleId(cycleId: String): Uni<List<SettlementPosition>>

    fun upsertPosition(
        participantBic: String,
        currency: String,
        cycleId: String,
        debit: BigDecimal,
        credit: BigDecimal,
    ): Uni<SettlementPosition>
}

/** Outbound port for publishing clearing domain events to the broker. */
interface ClearingEventPublisher {

    fun publishBatchSettled(batch: ClearingBatch): Uni<Void>

    fun publishItemCleared(item: ClearingItem): Uni<Void>
}
