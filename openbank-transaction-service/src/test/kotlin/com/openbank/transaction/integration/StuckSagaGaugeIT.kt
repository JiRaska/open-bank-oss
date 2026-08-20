// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.observability.StuckSagaGauge
import com.openbank.transaction.infrastructure.persistence.repository.PanacheTransactionRepository
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.micrometer.core.instrument.MeterRegistry
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Proves `openbank.transaction.sagas.stuck` — the series the critical, money-path
 * `TransactionSagaStuck` alert reads — is actually EMITTED, against a real database and a real
 * Micrometer registry (issue #5733: the rule watched a name nothing produced).
 *
 * It asserts three separate things, because the alert needs all three and the first two are the
 * ones a mocked test cannot see:
 *
 *  1. **The series exists at zero on a fresh pod.** `sagaGaugeValue()` is `0.0`, not absent. An
 *     absent series makes `openbank_transaction_sagas_stuck > 0` match nothing at all, which is
 *     indistinguishable from a healthy match of zero — so eager registration is the property that
 *     makes the rule able to be quiet *and* able to fire.
 *  2. **It rises on the real path.** A `PENDING` transaction older than the stuck threshold, read
 *     back by the real Panache query, moves the gauge above zero.
 *  3. **It only counts what it claims.** A recent `PENDING` saga and an old `COMPLETED` one are
 *     both excluded, so the alert cannot page on healthy traffic.
 *
 * ### Falsification (recorded so the next reader does not have to redo it)
 * Removing the `metrics.registerStuckPaymentSagas { … }` call from [StuckSagaGauge.register] makes
 * assertion 1 fail (`sagaGaugeValue()` returns null — the meter is gone); removing the
 * `cached.set(...)` from `refresh()` makes assertion 2 fail (the gauge stays at 0.0). Both were run
 * red before this test was committed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class StuckSagaGaugeIT {

    @Inject
    lateinit var gauge: StuckSagaGauge

    @Inject
    lateinit var repository: PanacheTransactionRepository

    @Inject
    lateinit var registry: MeterRegistry

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun sagaGaugeValue(): Double? = registry.find(DomainMetrics.STUCK_PAYMENT_SAGAS).gauge()?.value()

    @BeforeEach
    fun clearTransactions() {
        onEventLoop {
            Panache.withTransaction { repository.deleteAll() }.awaitSuspending()
        }
        onEventLoop { gauge.refresh() }
    }

    private fun seed(status: TransactionStatus, initiatedAt: Instant) {
        val id = Ids.newId()
        val czk = CurrencyCode("CZK")
        val tx = Transaction(
            id = id,
            referenceNumber = "TX-$id",
            type = TransactionType.TRANSFER,
            sourceAccountId = Ids.newId(),
            targetAccountId = Ids.newId(),
            amount = Money(BigDecimal("100.00"), czk),
            fxRate = null,
            baseAmount = Money(BigDecimal("100.00"), czk),
            status = status,
            description = "stuck-saga gauge fixture",
            valueDate = LocalDate.now(),
            bookingDate = LocalDate.now(),
            initiatedAt = initiatedAt,
            completedAt = if (status == TransactionStatus.COMPLETED) initiatedAt else null,
            failedAt = null,
            failureReason = null,
            idempotencyKey = "idem-$id",
            version = 0,
        )
        val outbox = OutboxMessage(
            aggregateId = id,
            eventType = "test.event.stuck-saga",
            payload = """{"id":"$id"}""",
            createdAt = initiatedAt,
        )
        onEventLoop { repository.save(tx, outbox) }
    }

    @Test
    fun `the gauge is registered at zero before any saga is stuck`() {
        assertThat(sagaGaugeValue())
            .describedAs("the series must EXIST at 0, not be absent — an absent series makes the alert match nothing")
            .isEqualTo(0.0)
    }

    @Test
    fun `a pending saga older than the threshold raises the gauge`() {
        seed(TransactionStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS))
        onEventLoop { gauge.refresh() }

        assertThat(sagaGaugeValue()).isEqualTo(1.0)
    }

    @Test
    fun `a processing saga older than the threshold raises the gauge`() {
        seed(TransactionStatus.PROCESSING, Instant.now().minus(1, ChronoUnit.HOURS))
        onEventLoop { gauge.refresh() }

        assertThat(sagaGaugeValue()).isEqualTo(1.0)
    }

    @Test
    fun `a recent pending saga and an old completed one leave the gauge at zero`() {
        seed(TransactionStatus.PENDING, Instant.now())
        seed(TransactionStatus.COMPLETED, Instant.now().minus(1, ChronoUnit.HOURS))
        onEventLoop { gauge.refresh() }

        assertThat(sagaGaugeValue())
            .describedAs("healthy traffic must not page: only non-terminal sagas past the threshold count")
            .isEqualTo(0.0)
    }
}
