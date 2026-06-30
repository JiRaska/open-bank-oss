// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.saga

// Terminal states for the payment saga / Temporal workflow execution (ADR-0120).
// Extracted from PaymentSaga.kt (Phase 5); PaymentWorkflow.execute() still returns this type.
enum class SagaState {
    STARTED,
    PAYMENT_INITIATED,
    FUNDS_RESERVED,
    LEDGER_POSTING,
    FUNDS_CAPTURED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED,
}
