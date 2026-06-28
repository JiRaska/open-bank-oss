// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.transaction.domain.saga.SagaState
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * ADR-0120 Phase 1 Temporal workflow for payment execution. Returns the terminal [SagaState] so the
 * call site (`TransactionService`) keeps the exact same `state == COMPLETED` branch as the legacy
 * `PaymentSagaOrchestrator` path.
 */
@WorkflowInterface
interface PaymentWorkflow {
    @WorkflowMethod
    fun execute(transactionId: UUID): SagaState
}
