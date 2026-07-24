// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.standingorder.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.PaymentType
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import com.openbank.standingorder.infrastructure.persistence.repository.StandingOrderRepositoryImpl
import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Regression coverage for the pause/cancel/resume HTTP 500 (#2077): the aggregate has an
 * application-assigned `@Id`, so [StandingOrderRepositoryImpl.save] must `merge` (upsert) — a
 * `persist` schedules an INSERT and duplicate-keys on `standing_orders_pkey` the moment `save`
 * is called for an *existing* row, which is exactly what pause/resume/cancel/confirm do.
 *
 * This drives the repository through a real Vert.x context (a mocked repo cannot see the flush),
 * seeding via `save` then re-saving a state transition on the same id — the second `save` threw
 * `duplicate key value violates unique constraint "standing_orders_pkey"` before the fix.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class StandingOrderSaveUpsertIT {

    @Inject
    lateinit var repository: StandingOrderRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun newOrder(id: UUID): StandingOrder {
        val now = Instant.now()
        return StandingOrder(
            id = id,
            idempotencyKey = "upsert-it-$id",
            partyId = Ids.newId(),
            debitAccountId = Ids.newId(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Debtor",
            creditorIban = "DE75512108001245126199",
            creditorName = "Bob Creditor",
            creditorBic = null,
            amountMinorUnits = 1_000,
            currency = "EUR",
            frequency = Frequency.MONTHLY,
            paymentType = PaymentType.SEPA_CREDIT,
            remittanceInfo = null,
            startDate = LocalDate.of(2026, 1, 1),
            endDate = null,
            nextExecutionDate = LocalDate.of(2026, 1, 1),
            lastExecutionDate = null,
            executionCount = 0,
            failureCount = 0,
            status = StandingOrderStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `cancel re-saves an existing order without a duplicate-key error`() {
        val id = Ids.newId()
        onEventLoop { repository.save(newOrder(id)) }

        assertThatCode {
            onEventLoop { repository.save(newOrder(id).cancel(Instant.now())) }
        }.describedAs("re-saving an existing order must upsert, not INSERT").doesNotThrowAnyException()

        val reloaded = onEventLoop { repository.findById(id) }
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.status).isEqualTo(StandingOrderStatus.CANCELLED)
    }

    @Test
    fun `pause re-saves an existing order without a duplicate-key error`() {
        val id = Ids.newId()
        onEventLoop { repository.save(newOrder(id)) }

        assertThatCode {
            onEventLoop { repository.save(newOrder(id).pause(Instant.now())) }
        }.describedAs("re-saving an existing order must upsert, not INSERT").doesNotThrowAnyException()

        val reloaded = onEventLoop { repository.findById(id) }
        assertThat(reloaded!!.status).isEqualTo(StandingOrderStatus.PAUSED)
    }
}
