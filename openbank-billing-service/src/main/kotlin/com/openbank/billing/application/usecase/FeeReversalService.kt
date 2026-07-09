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
        val existing = resolveReversible(idempotencyKey)
        if (existing.alreadyReversed) {
            log.debugf(
                "fee idempotencyKey=%s is already %s — returning existing (idempotent replay, no second reversal)",
                idempotencyKey.replace('\n', '_').replace('\r', '_'),
                existing.fee.postingStatus,
            )
            return existing.fee
        }

        // The single direct throw left in this function body (detekt ThrowsCount, max 2 — the
        // other two live in resolveReversible, mirrors KycService.validateReason/rejectCase):
        // persistReversalIntent returning null here means the fee vanished between
        // resolveReversible's read and this call — essentially impossible (AssessedFee rows are
        // append-only, never deleted) but defended against rather than silently NPEing.
        val updated = repository.persistReversalIntent(idempotencyKey, reason)
            ?: throw FeeNotFoundException(idempotencyKey)
        log.infof(
            "fee idempotencyKey=%s reversal intent committed (reason=%s) — awaiting outbox dispatch",
            idempotencyKey.replace('\n', '_').replace('\r', '_'),
            reason.replace('\n', '_').replace('\r', '_'),
        )
        return updated
    }

    /**
     * Resolves the fee for [idempotencyKey] and validates it is reversible, throwing the two
     * "can't reverse this" exceptions the ADR calls out (extracted so [reverse]'s own body stays
     * within detekt's `ThrowsCount` limit — mirrors `KycService.validateReason`). A single fetch:
     * [ReversibleFee.alreadyReversed] carries the idempotent-replay outcome inline rather than
     * forcing [reverse] to re-fetch the same row (racy and wasteful) to tell "replay" apart from
     * "needs a fresh lookup".
     */
    private suspend fun resolveReversible(idempotencyKey: String): ReversibleFee {
        val existing = repository.findFeeByIdempotencyKey(idempotencyKey)
            ?: throw FeeNotFoundException(idempotencyKey)
        if (existing.postingStatus == PostingStatus.REVERSAL_PENDING ||
            existing.postingStatus == PostingStatus.REVERSED
        ) {
            return ReversibleFee(existing, alreadyReversed = true)
        }
        if (existing.postingStatus != PostingStatus.POSTED) {
            throw FeeNotPostedException(idempotencyKey, existing.postingStatus)
        }
        return ReversibleFee(existing, alreadyReversed = false)
    }

    private data class ReversibleFee(val fee: AssessedFee, val alreadyReversed: Boolean)
}

/** No [AssessedFee] with this idempotency key has ever been persisted. */
class FeeNotFoundException(idempotencyKey: String) :
    IllegalArgumentException("no assessed fee with idempotencyKey=$idempotencyKey")

/** The fee exists but was never POSTED (waived, zero-amount, still PENDING, or FAILED) — nothing to reverse. */
class FeeNotPostedException(idempotencyKey: String, actual: PostingStatus) :
    IllegalStateException("fee idempotencyKey=$idempotencyKey is $actual, not POSTED — nothing to reverse")
