// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.integration

import com.openbank.balance.infrastructure.persistence.repository.BalancePanacheRepo
import com.openbank.balance.infrastructure.persistence.repository.BalanceRepositoryImpl
import com.openbank.balance.infrastructure.persistence.repository.LedgerProjectionEventPanacheRepo
import com.openbank.balance.infrastructure.persistence.repository.LedgerProjectionPortImpl
import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0178: `sumBookedByCurrencyAsOf` must reconcile on the ledger's value-date basis — the
 * materialized booked balance minus the future-value-dated tail (`entry_date > asOf`) — so a journal
 * value-dated after the tie-out date is excluded, exactly as the ledger trial balance excludes it.
 * Regression coverage for the value-date false-drift class (five welcome bonuses value-dated one day
 * forward surfaced as a +500k CZK "drift" that self-heals on the value date).
 *
 * Seeds through the real projection path ([LedgerProjectionPortImpl.applyBookedDelta]) so `balances`
 * and `ledger_projection_event` stay consistent, then asserts the as-of sum excludes the future tail
 * while the plain aggregate does not.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class BalanceReconciliationAsOfIT {

    @Inject
    lateinit var balanceRepo: BalanceRepositoryImpl

    @Inject
    lateinit var projection: LedgerProjectionPortImpl

    @Inject
    lateinit var balancePanacheRepo: BalancePanacheRepo

    @Inject
    lateinit var projectionRepo: LedgerProjectionEventPanacheRepo

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun clean() = onEventLoop {
        Panache.withTransaction {
            projectionRepo.deleteAll().flatMap { balancePanacheRepo.deleteAll() }
        }.awaitSuspending()
    }

    @Test
    fun `sumBookedByCurrencyAsOf excludes future-value-dated deltas, anchored on the materialized balance`() {
        val asOf = LocalDate.of(2026, 7, 18)
        val acctCzk = UUID.randomUUID()
        val acctEur = UUID.randomUUID()
        clean()

        onEventLoop {
            // CZK 1000 effective (entry_date <= asOf) + 500 value-dated two days forward = 1500 booked.
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctCzk,
                currency = "CZK",
                delta = BigDecimal("1000.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.minusDays(1),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctCzk,
                currency = "CZK",
                delta = BigDecimal("500.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.plusDays(2),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
            // EUR 200, all effective — no future tail.
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctEur,
                currency = "EUR",
                delta = BigDecimal("200.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.minusDays(1),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
        }

        val current = onEventLoop { balanceRepo.sumBookedByCurrency() }
        val asOfSum = onEventLoop { balanceRepo.sumBookedByCurrencyAsOf(asOf) }

        // Materialized (receipt-dated) total includes the future-value-dated 500.
        assertThat(current["CZK"]).isEqualByComparingTo("1500.00")
        assertThat(current["EUR"]).isEqualByComparingTo("200.00")
        // Value-date basis: the 500 dated after asOf is excluded; the 1000 on/before asOf stays.
        assertThat(asOfSum["CZK"]).isEqualByComparingTo("1000.00")
        assertThat(asOfSum["EUR"]).isEqualByComparingTo("200.00")
    }

    /**
     * ADR-0178 Phase 3 — the pipeline the tie-out subtracts must also be readable on its own, against
     * a REAL database. A mocked repository cannot prove the `entry_date > :asOf` grouping or the
     * `sumBookedByCurrencyAsOf = sumBookedByCurrency − pipeline` identity that the operator-facing
     * explanation rests on; it would happily return whatever the fake was told to.
     */
    @Test
    fun `sumFutureValueDatedByCurrency reports the tail that the as-of sum subtracts`() {
        val asOf = LocalDate.of(2026, 7, 18)
        val acctCzk = UUID.randomUUID()
        val acctEur = UUID.randomUUID()
        clean()

        onEventLoop {
            // CZK: 1000 effective + two future-value-dated credits (500 + 250) = 750 pipeline.
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctCzk,
                currency = "CZK",
                delta = BigDecimal("1000.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.minusDays(1),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctCzk,
                currency = "CZK",
                delta = BigDecimal("500.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.plusDays(2),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctCzk,
                currency = "CZK",
                delta = BigDecimal("250.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.plusDays(5),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
            // EUR: entirely effective — must NOT appear in the pipeline at all.
            projection.applyBookedDelta(
                journalEntryId = UUID.randomUUID(),
                accountId = acctEur,
                currency = "EUR",
                delta = BigDecimal("200.00"),
                transactionId = UUID.randomUUID(),
                entryDate = asOf.minusDays(1),
                actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
            )
        }

        val pipeline = onEventLoop { balanceRepo.sumFutureValueDatedByCurrency(asOf) }
        val current = onEventLoop { balanceRepo.sumBookedByCurrency() }
        val asOfSum = onEventLoop { balanceRepo.sumBookedByCurrencyAsOf(asOf) }

        assertThat(pipeline["CZK"]).isEqualByComparingTo("750.00")
        // A currency with no future-value-dated movement must be absent, not a spurious zero row.
        assertThat(pipeline).doesNotContainKey("EUR")

        // The identity the operator-facing explanation rests on: the pipeline is exactly the gap
        // between the raw materialized total and the value-date-correct tie-out figure.
        assertThat(current.getValue("CZK").subtract(asOfSum.getValue("CZK"))).isEqualByComparingTo("750.00")
        assertThat(current.getValue("EUR").subtract(asOfSum.getValue("EUR"))).isEqualByComparingTo("0.00")

        // An asOf beyond every value date leaves nothing in the pipeline — it drains, never accumulates.
        val drained = onEventLoop { balanceRepo.sumFutureValueDatedByCurrency(asOf.plusDays(10)) }
        assertThat(drained).isEmpty()
    }
}
