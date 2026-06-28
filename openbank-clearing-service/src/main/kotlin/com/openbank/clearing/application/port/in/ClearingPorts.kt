// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.port.`in`

import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.ReconciliationReport
import com.openbank.clearing.domain.model.SettlementPosition
import com.openbank.clearing.domain.model.SubmitPaymentRequest
import io.smallrye.mutiny.Uni
import java.util.UUID

interface SubmitPaymentUseCase {
    fun submit(request: SubmitPaymentRequest): Uni<ClearingItem>
}

interface GetBatchUseCase {
    fun getBatch(id: UUID): Uni<ClearingBatch?>
    fun listBatches(status: ClearingStatus? = null, page: Int = 0, size: Int = 50): Uni<List<ClearingBatch>>
}

interface GetItemUseCase {
    fun getItem(id: UUID): Uni<ClearingItem?>
    fun listItemsByBatch(batchId: UUID): Uni<List<ClearingItem>>
    fun listItemsByPayment(paymentId: UUID): Uni<List<ClearingItem>>
}

interface TriggerClearingUseCase {
    fun triggerClearingCycle(rail: PaymentRail): Uni<ClearingBatch>
    fun settleBatch(batchId: UUID): Uni<ClearingBatch>
}

interface GetPositionsUseCase {
    fun getPositions(cycleId: String): Uni<List<SettlementPosition>>
}

interface ReconcileUseCase {
    fun reconcileBatch(batchId: UUID): Uni<ReconciliationReport>
}
