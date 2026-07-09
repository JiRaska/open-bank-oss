// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.PostingStatus
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * A wrongly-charged (already-POSTED) fee's reversal (ADR-0143 phase 2e, milestone 2e): commits the
 * intent to post a COMPENSATING ledger journal — CREDIT the customer fee-receivable GL, DEBIT the
 * bank fee-income GL, the exact reverse of [BillingCycleService]/[FeeAssessmentService]'s charge
 * leg — through the same transactional outbox as the charge (ADR-0143 step 2's atomicity applies
 * identically here: the `posting_status -> REVERSAL_PENDING` flip and the outbox row commit in one
 * transaction, `BillingAssessmentRepositoryImpl.persistReversalIntent`).
 *
 * This is the REST `POST /api/v1/fees/reverse` endpoint's seam (`BillingResource`), gated by the
 * four-eyes `billing.reverse` action exactly like `billing.post` gates the charge — reuses the
 * SAME `AuthorizeInterceptor` + `ApprovalStore` infrastructure (ADR-0155), no new approval
 * mechanism.
 *
 * **Fails cleanly, never throws a generic 500** for the two "can't reverse this" cases the ADR
 * calls out:
 *  - the fee was never assessed at all (`idempotencyKey` unknown) -> [FeeNotFoundException].
 *  - the fee was assessed but never POSTED (waived/zero/still PENDING/FAILED) -> nothing to
 *    compensate -> [FeeNotPostedException].
 *
 * **Idempotent**: reversing an already-REVERSAL_PENDING or already-REVERSED fee returns the
 * existing (unchanged) fee rather than posting a second compensating journal — mirrors the charge
 * leg's own idempotent-replay contract (ADR-0143 step 1), enforced one layer down in
 * `BillingAssessmentRepositoryImpl.persistReversalIntent`.
 */
@ApplicationScoped
class FeeReversalService(private val repository: BillingAssessmentRepository) {
    private val log = Logger.getLogger(FeeReversalService::class.java)

    // idempotencyKey/reason below can trace back to caller input (REST path/body) — CR/LF
    // stripped inline at the log sink, same convention as BillingCycleService (CodeQL
    // java/log-injection is a local syntactic match on the replace() call at the sink itself).
    suspend fun reverse(idempotencyKey: String, reason: String): AssessedFee {
        val existing = repository.findFeeByIdempotencyKey(idempotencyKey)
            ?: throw FeeNotFoundException(idempotencyKey)

        if (existing.postingStatus == PostingStatus.REVERSAL_PENDING || existing.postingStatus == PostingStatus.REVERSED) {
            log.debugf(
                "fee idempotencyKey=%s is already %s — returning existing (idempotent replay, no second reversal)",
                idempotencyKey.replace('\n', '_').replace('\r', '_'),
                existing.postingStatus,
            )
            return existing
        }

        if (existing.postingStatus != PostingStatus.POSTED) {
            throw FeeNotPostedException(idempotencyKey, existing.postingStatus)
        }

        val updated = repository.persistReversalIntent(idempotencyKey, reason)
            ?: throw FeeNotFoundException(idempotencyKey)
        log.infof(
            "fee idempotencyKey=%s reversal intent committed (reason=%s) — awaiting outbox dispatch",
            idempotencyKey.replace('\n', '_').replace('\r', '_'),
            reason.replace('\n', '_').replace('\r', '_'),
        )
        return updated
    }
}

/** No [AssessedFee] with this idempotency key has ever been persisted. */
class FeeNotFoundException(idempotencyKey: String) :
    IllegalArgumentException("no assessed fee with idempotencyKey=$idempotencyKey")

/** The fee exists but was never POSTED (waived, zero-amount, still PENDING, or FAILED) — nothing to reverse. */
class FeeNotPostedException(idempotencyKey: String, actual: PostingStatus) :
    IllegalStateException("fee idempotencyKey=$idempotencyKey is $actual, not POSTED — nothing to reverse")
