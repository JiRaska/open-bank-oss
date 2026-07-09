// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.usecase.FeeNotFoundException
import com.openbank.billing.application.usecase.FeeNotPostedException
import com.openbank.billing.application.usecase.FeeReversalService
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.domain.PostingStatus
import com.openbank.billing.infrastructure.outbox.BillingOutboxRepositoryImpl
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.product.WaiveReason
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Integration coverage against real Postgres for the fee-reversal flow (ADR-0143 phase 2e): the
 * `posting_status -> REVERSAL_PENDING` flip and the compensating-journal outbox row commit in the
 * SAME transaction, a reversal replay is idempotent (no second outbox row), and a fee that was
 * never posted (or never assessed) fails cleanly rather than with a generic 500 — proving the V4
 * migration's schema (widened `billing_posting_status` enum + `reversal_*` columns) round-trips
 * correctly against a real DB, not just in-memory mocks.
 *
 * Uses [VertxContextSupport.subscribeAndAwait] to bridge onto a Vert.x duplicated context, same as
 * `BillingCycleServiceIT` — Panache reactive calls need one; a bare `runBlocking` test thread has
 * none ("No current Vertx context found").
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class FeeReversalServiceIT {

    @Inject
    lateinit var reversalService: FeeReversalService

    @Inject
    lateinit var repository: BillingAssessmentRepository

    @Inject
    lateinit var outboxRepository: BillingOutboxRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    /** Persists a POSTED fee directly (bypassing the read-path adapters, unavailable in this IT profile). */
    private suspend fun postedFee(cycleId: String, accountId: String, feeId: String, journalId: UUID): AssessedFee {
        val fee = AssessedFee(
            cycleId = cycleId,
            accountId = accountId,
            feeId = feeId,
            name = "Maintenance",
            currency = "CZK",
            chargedAmount = BigDecimal("150.00"),
            waived = false,
            reason = WaiveReason.NOT_WAIVABLE,
        )
        val assessment =
            BillingAssessment(cycleId, accountId, "CZK", skipped = false, skipReason = null, assessedFees = listOf(fee))
        repository.persistWithPostingIntent(assessment)
        repository.markPosted(fee.idempotencyKey, journalId)
        return checkNotNull(repository.findFeeByIdempotencyKey(fee.idempotencyKey))
    }

    @Test
    fun `reversing a POSTED fee flips it to REVERSAL_PENDING and appends exactly one reversal outbox row`(): Unit =
        onVertxContext {
            val cycleId = "it-reversal-cycle-${System.nanoTime()}"
            val posted = postedFee(cycleId, "acc-reversal-1", "f1", UUID.randomUUID())
            assertThat(posted.postingStatus).isEqualTo(PostingStatus.POSTED)

            val backlogBefore = outboxRepository.countProcessable()
            val reversed = reversalService.reverse(posted.idempotencyKey, "waiver bug")

            assertThat(reversed.postingStatus).isEqualTo(PostingStatus.REVERSAL_PENDING)
            assertThat(outboxRepository.countProcessable()).isEqualTo(backlogBefore + 1)
        }

    @Test
    fun `reversing the same fee twice is idempotent — only one reversal outbox row is ever appended`(): Unit =
        onVertxContext {
            val cycleId = "it-reversal-cycle-${System.nanoTime()}"
            val posted = postedFee(cycleId, "acc-reversal-2", "f1", UUID.randomUUID())

            reversalService.reverse(posted.idempotencyKey, "waiver bug")
            val backlogAfterFirst = outboxRepository.countProcessable()

            val second = reversalService.reverse(posted.idempotencyKey, "retry")
            assertThat(second.postingStatus).isEqualTo(PostingStatus.REVERSAL_PENDING)
            assertThat(outboxRepository.countProcessable()).isEqualTo(backlogAfterFirst)
        }

    @Test
    fun `reversing a fee that was never posted fails cleanly instead of appending an outbox row`(): Unit =
        onVertxContext {
            val cycleId = "it-reversal-cycle-${System.nanoTime()}"
            val assessment = BillingAssessment(
                cycleId,
                "acc-reversal-3",
                "CZK",
                skipped = false,
                skipReason = null,
                assessedFees = listOf(
                    AssessedFee(
                        cycleId = cycleId,
                        accountId = "acc-reversal-3",
                        feeId = "f1",
                        name = "Maintenance",
                        currency = "CZK",
                        chargedAmount = BigDecimal.ZERO,
                        waived = true,
                        reason = WaiveReason.WAIVED_BY_CONDITION,
                    ),
                ),
            )
            val persisted = repository.persistWithPostingIntent(assessment)
            val fee = persisted.assessedFees.single()
            assertThat(fee.postingStatus).isEqualTo(PostingStatus.NOT_APPLICABLE)

            val backlogBefore = outboxRepository.countProcessable()
            assertThatThrownBy { runBlockingReverse(fee.idempotencyKey, "oops") }
                .isInstanceOf(FeeNotPostedException::class.java)
            assertThat(outboxRepository.countProcessable()).isEqualTo(backlogBefore)
        }

    @Test
    fun `reversing a fee that was never assessed fails cleanly with FeeNotFoundException`(): Unit = onVertxContext {
        assertThatThrownBy { runBlockingReverse("fee-no-such-key-CZK", "oops") }
            .isInstanceOf(FeeNotFoundException::class.java)
    }

    // A suspend call inside assertThatThrownBy's non-suspend lambda needs its own bridge —
    // onVertxContext's outer suspend body already runs on the Vert.x context, so nesting another
    // runBlocking here (not another onVertxContext, which would open a SECOND context) is safe.
    private fun runBlockingReverse(idempotencyKey: String, reason: String): AssessedFee =
        kotlinx.coroutines.runBlocking { reversalService.reverse(idempotencyKey, reason) }
}
