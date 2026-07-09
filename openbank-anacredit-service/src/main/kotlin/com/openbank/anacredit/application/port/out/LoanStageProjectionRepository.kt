// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application.port.out

import com.openbank.anacredit.domain.model.LoanStageProjection
import java.util.UUID

/**
 * Durable "last known IFRS 9 stage per loan" read-model (ADR-0037 event-ingestion follow-up,
 * issue #638) — anacredit-service's first persisted state; see `V1__create_loan_stage_projection.sql`.
 *
 * `suspend` (reactive Panache under the implementation), matching the fleet's Kafka-consumer +
 * reactive-persistence convention (e.g. `openbank-lending-service`'s outbox repository) — the rest of
 * `AnaCreditService`'s existing synchronous ports are untouched by this addition.
 */
interface LoanStageProjectionRepository {
    suspend fun findByLoanId(loanId: UUID): LoanStageProjection?

    /**
     * Insert or update the projection for [projection.loanId], but **only** if no row exists yet or the
     * existing row's `eventTimestamp` is strictly older than [projection.eventTimestamp]. Returns `true`
     * if the row was written, `false` if an existing, equally-or-more-recent row made this a no-op — the
     * idempotency guard against duplicate/out-of-order delivery (at-least-once Kafka + redelivery).
     */
    suspend fun applyIfNewer(projection: LoanStageProjection): Boolean
}
