// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.model.VelocityAggregate
import com.openbank.fraud.domain.model.VelocityWindow
import java.math.BigDecimal
import java.util.UUID

/** Stores and queries per-account rolling velocity aggregates (ADR-0084 §2). */
interface VelocityAggregateRepository {
    /**
     * Records a new transaction signal for [accountId], identified by [transactionId] (the signal's
     * `aggregateId`). Upserts all three windows (H1/H24/D7) in a single call — incrementing the
     * count and sum within each window's time bucket.
     *
     * Idempotent per window (#5716): each window row carries the id of the last signal applied to
     * it, and a signal whose [transactionId] matches is skipped. So a redelivered Kafka message does
     * not double-count, and a retry after a partial failure re-applies only the windows that were
     * not applied — the windows converge independently rather than as one transaction. A null
     * [transactionId] carries no identity and is therefore never deduplicated (same contract as
     * [PayeeHistoryRepository.recordPayment]).
     */
    suspend fun recordTransaction(accountId: UUID, amount: BigDecimal, currency: String, transactionId: UUID?)

    /**
     * Returns the current aggregate for [accountId] in [window] for [currency], or null.
     * Currency is part of the bucket key — amounts in different currencies never mix.
     */
    suspend fun findAggregate(accountId: UUID, window: VelocityWindow, currency: String): VelocityAggregate?
}
