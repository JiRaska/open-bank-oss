// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.infrastructure.persistence.repository

import com.openbank.anacredit.domain.model.LoanStageProjection
import com.openbank.anacredit.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Exercises [LoanStageProjectionRepositoryImpl] against a real Postgres (Testcontainers) — the
 * idempotent/ordering-safe `applyIfNewer` upsert is the crux of the whole `loan.stage_changed`
 * ingestion path (ADR-0037 follow-up, issue #638) and cannot be verified with a mock.
 *
 * `LoanStageProjectionRepositoryImpl` is reactive (`Panache.withSession/withTransaction`), so its
 * suspend calls must run on a Vert.x duplicated context (mirrors `openbank-ledger-service`'s
 * `JournalPartitionMaintainerIT` — a plain `runBlocking` test thread has none).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LoanStageProjectionRepositoryIT {

    @Inject
    lateinit var repository: LoanStageProjectionRepositoryImpl

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    @Test
    fun `a first-ever event for a loan is always applied`(): Unit = onVertxContext {
        val loanId = UUID.randomUUID()
        val applied = repository.applyIfNewer(
            LoanStageProjection(
                loanId = loanId,
                stage = "STAGE_1",
                daysPastDue = 0,
                eventTimestamp = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
            ),
        )

        assertThat(applied).isTrue()
        val stored = repository.findByLoanId(loanId)
        assertThat(stored?.stage).isEqualTo("STAGE_1")
    }

    @Test
    fun `a newer event overwrites the projection`(): Unit = onVertxContext {
        val loanId = UUID.randomUUID()
        repository.applyIfNewer(
            LoanStageProjection(
                loanId = loanId,
                stage = "STAGE_1",
                daysPastDue = 0,
                eventTimestamp = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
            ),
        )

        val applied = repository.applyIfNewer(
            LoanStageProjection(
                loanId = loanId,
                stage = "STAGE_2",
                daysPastDue = 40,
                eventTimestamp = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            ),
        )

        assertThat(applied).isTrue()
        val stored = repository.findByLoanId(loanId)
        assertThat(stored?.stage).isEqualTo("STAGE_2")
        assertThat(stored?.daysPastDue).isEqualTo(40)
    }

    @Test
    fun `an out-of-order older event is rejected and never regresses the projection`(): Unit = onVertxContext {
        val loanId = UUID.randomUUID()
        repository.applyIfNewer(
            LoanStageProjection(
                loanId = loanId,
                stage = "STAGE_2",
                daysPastDue = 40,
                eventTimestamp = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            ),
        )

        // A stale re-delivery of an earlier (already superseded) transition.
        val applied = repository.applyIfNewer(
            LoanStageProjection(
                loanId = loanId,
                stage = "STAGE_1",
                daysPastDue = 0,
                eventTimestamp = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
            ),
        )

        assertThat(applied).isFalse()
        val stored = repository.findByLoanId(loanId)
        assertThat(stored?.stage).isEqualTo("STAGE_2")
        assertThat(stored?.daysPastDue).isEqualTo(40)
    }

    @Test
    fun `a duplicate event at the same timestamp is a no-op`(): Unit = onVertxContext {
        val loanId = UUID.randomUUID()
        val projection = LoanStageProjection(
            loanId = loanId,
            stage = "STAGE_2",
            daysPastDue = 40,
            eventTimestamp = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )
        repository.applyIfNewer(projection)

        val appliedAgain = repository.applyIfNewer(projection)

        assertThat(appliedAgain).isFalse()
    }

    @Test
    fun `an unknown loan has no projection`(): Unit = onVertxContext {
        assertThat(repository.findByLoanId(UUID.randomUUID())).isNull()
    }
}
