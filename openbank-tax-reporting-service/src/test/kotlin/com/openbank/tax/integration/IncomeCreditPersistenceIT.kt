// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.tax.domain.returns.ObservedIncomeCredit
import com.openbank.tax.infrastructure.persistence.PanacheIncomeCreditRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Real-DB proof of the two properties a mocked repository cannot show (ADR-0340 B):
 *  - `record` is idempotent on withholding_id — a Kafka redelivery does not double an account's
 *    gross into a CRS line;
 *  - `totalsByAccount` maps the HQL aggregate rows (Object[] of accountId, currency, sum) onto
 *    [com.openbank.tax.domain.returns.IncomeCreditTotals] correctly, over the window predicate on
 *    the accrual period END.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_income_credit_it")],
)
class IncomeCreditPersistenceIT {

    @Inject
    lateinit var repository: PanacheIncomeCreditRepository

    @Inject
    lateinit var vertx: Vertx

    private fun <T> onVertxContext(block: suspend () -> T): T {
        val future = CompletableFuture<T>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(30, TimeUnit.SECONDS)
    }

    private val accountA = UUID.fromString("0a000000-0000-0000-0000-000000000001")
    private val accountB = UUID.fromString("0a000000-0000-0000-0000-000000000002")

    private fun credit(
        withholdingId: UUID,
        accountId: UUID,
        gross: String,
        periodTo: LocalDate,
        currency: String = "CZK",
    ) = ObservedIncomeCredit(
        withholdingId = withholdingId,
        capitalizationId = UUID.randomUUID(),
        accountId = accountId,
        productId = UUID.fromString("99999999-8888-4777-8666-555555555555"),
        periodFrom = periodTo.withDayOfMonth(1),
        periodTo = periodTo,
        currency = currency,
        grossAmount = BigDecimal(gross),
        netAmount = BigDecimal(gross),
        occurredAt = Instant.parse("2026-07-31T23:59:59Z"),
        observedAt = Instant.parse("2026-08-05T09:00:00Z"),
    )

    @Test
    fun `a redelivered record is a no-op and totals group per account and currency over the window`() = runBlocking {
        val jan = LocalDate.of(2026, 1, 31)
        val jun = LocalDate.of(2026, 6, 30)
        val dec = LocalDate.of(2026, 12, 31)

        onVertxContext {
            val w1 = UUID.fromString("10000000-0000-0000-0000-000000000001")
            // accountA: one January credit and a redelivery of it, plus a EUR credit in June.
            assertThat(repository.record(credit(w1, accountA, "100.00", jan))).isTrue()
            assertThat(repository.record(credit(w1, accountA, "100.00", jan))).isFalse()
            assertThat(
                repository.record(
                    credit(
                        UUID.fromString("10000000-0000-0000-0000-000000000002"),
                        accountA,
                        "50.00",
                        jun,
                        currency = "EUR",
                    ),
                ),
            ).isTrue()
            // accountB: one June credit, and a credit whose period ENDS just past the window.
            assertThat(
                repository.record(
                    credit(UUID.fromString("10000000-0000-0000-0000-000000000003"), accountB, "7.50", jun),
                ),
            ).isTrue()
            assertThat(
                repository.record(
                    credit(UUID.fromString("10000000-0000-0000-0000-000000000004"), accountB, "999.00", dec),
                ),
            ).isTrue()
        }

        val totals = onVertxContext {
            repository.totalsByAccount(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))
        }
        // The redelivery must not double accountA's CZK gross; the out-of-window December
        // credit must not appear; EUR and CZK stay separate totals.
        assertThat(totals).containsExactlyInAnyOrder(
            com.openbank.tax.domain.returns.IncomeCreditTotals(accountA, "CZK", BigDecimal("100.00")),
            com.openbank.tax.domain.returns.IncomeCreditTotals(accountA, "EUR", BigDecimal("50.00")),
            com.openbank.tax.domain.returns.IncomeCreditTotals(accountB, "CZK", BigDecimal("7.50")),
        )
    }
}
