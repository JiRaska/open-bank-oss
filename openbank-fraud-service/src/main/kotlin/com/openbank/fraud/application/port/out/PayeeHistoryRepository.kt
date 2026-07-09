// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.model.PayeeHistory
import java.time.Instant
import java.util.UUID

/** Stores and queries per-(account, payee) payment history (ADR-0084 §3 v4). */
interface PayeeHistoryRepository {
    /**
     * Records a transaction signal from [accountId] to [payeeIdentifier], identified by
     * [transactionId] (the signal's `aggregateId`) at [occurredAt]. Upserts the
     * `(account_id, payee_identifier)` row: first call creates it with `payment_count = 1`;
     * subsequent calls increment the count and advance `last_paid_at`.
     *
     * Idempotent: replaying the same [transactionId] for a pair that already recorded it is a
     * no-op — the count is not incremented twice for one underlying payment.
     */
    suspend fun recordPayment(accountId: UUID, payeeIdentifier: String, transactionId: UUID?, occurredAt: Instant)

    /** Returns the history for [accountId] paying [payeeIdentifier], or null if never paid before. */
    suspend fun findHistory(accountId: UUID, payeeIdentifier: String): PayeeHistory?
}
