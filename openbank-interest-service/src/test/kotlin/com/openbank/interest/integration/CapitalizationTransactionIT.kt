// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTreatment
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestRateConfigEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Proves the ADR-0033 capitalization atomicity claim against a REAL Postgres — the thing the mocked
 * use-case tests structurally cannot show. `InterestCapitalizationRepositoryImpl.saveWithOutbox`
 * asserts that the capitalization, the withholding liability, the outbox event and the
 * `ACCRUING → CAPITALIZED` flip either all commit or all roll back; only a live transaction can
 * demonstrate that no partial rows survive a mid-way failure.
 *
 * Why this matters concretely: the pre-refactor code ran these as four separate transactions, so a
 * crash after the capitalization commit but before the accrual flip left the accruals `ACCRUING`
 * next to a committed capitalization. The retry then re-credited the customer AND re-booked the
 * withholding tax — a double credit and a double tax liability from one period's interest.
 *
 * Also exercises the V6 partial unique index, the DB backstop for the same invariant.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.interest.it.PostgresRedisTestResource::class)
class CapitalizationTransactionIT {

    @Inject
    lateinit var capitalizationRepo: InterestCapitalizationRepository

    @Inject
    lateinit var sf: Mutiny.SessionFactory

    private val czk = "CZK"

    /** interest_accruals.config_id is a real FK, so every accrual needs a rate config to point at. */
    private fun persistConfig(productId: String): UUID {
        val entity = InterestRateConfigEntity().apply {
            id = UUID.randomUUID()
            this.productId = productId
            annualRate = BigDecimal("0.365000")
            minBalance = BigDecimal.ZERO
            maxBalance = BigDecimal("1000000.0000")
            effectiveFrom = LocalDate.of(2026, 1, 1)
            active = true
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
            updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        }
        VertxContextSupport.subscribeAndAwait { sf.withTransaction { s -> s.persist(entity) } }
        return entity.id
    }

    // Reactive Panache/Hibernate must run on a Vert.x duplicated context; the JUnit thread is not
    // one, so every DB interaction goes through VertxContextSupport.subscribeAndAwait.
    private fun persistAccrual(
        accountId: UUID,
        productId: String,
        status: AccrualStatus,
        accrualDate: LocalDate = LocalDate.of(2026, 1, 18),
    ): UUID {
        val entity = InterestAccrualEntity().apply {
            id = UUID.randomUUID()
            this.accountId = accountId
            this.productId = productId
            configId = persistConfig(productId)
            this.accrualDate = accrualDate
            balance = BigDecimal("1000.00")
            dailyRate = BigDecimal("0.0010000000")
            accruedAmount = BigDecimal("100.000000")
            currency = czk
            this.status = status
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        }
        VertxContextSupport.subscribeAndAwait { sf.withTransaction { s -> s.persist(entity) } }
        return entity.id
    }

    private fun capitalizationOf(accountId: UUID, periodTo: LocalDate = LocalDate.of(2026, 1, 20)) =
        InterestCapitalization(
            accountId = accountId,
            productId = "SAVINGS_CZK",
            periodFrom = LocalDate.of(2026, 1, 18),
            periodTo = periodTo,
            totalAccrued = BigDecimal("100.000000"),
            capitalizedAmount = BigDecimal("85.0000"),
            grossAmount = BigDecimal("100.0000"),
            taxAmount = BigDecimal("15.0000"),
            netAmount = BigDecimal("85.0000"),
            currency = czk,
            createdAt = OffsetDateTime.parse("2026-01-20T00:00:00Z"),
        )

    private fun withholdingOf(cap: InterestCapitalization) = WithholdingTax(
        capitalizationId = cap.id,
        accountId = cap.accountId,
        periodFrom = cap.periodFrom,
        periodTo = cap.periodTo,
        taxableBase = BigDecimal("100.0000"),
        rate = BigDecimal("0.1500"),
        taxAmount = BigDecimal("15.0000"),
        currency = czk,
        treatment = WithholdingTreatment.WITHHELD,
        createdAt = cap.createdAt,
    )

    private fun eventOf(cap: InterestCapitalization) = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = cap.id,
        eventType = "interest.withholding.recorded.v1",
        payload = """{"schemaVersion":1,"capitalizationId":"${cap.id}"}""",
    )

    /** Drives the reactive single-transaction write on a Vert.x context and waits for its outcome. */
    private fun saveCapitalization(cap: InterestCapitalization, accrualIds: List<UUID>) {
        VertxContextSupport.subscribeAndAwait {
            capitalizationRepo.saveWithOutbox(
                cap,
                withholdingOf(cap),
                eventOf(cap),
                accrualIds,
                OffsetDateTime.parse("2026-01-20T00:00:00Z"),
            ).replaceWithVoid()
        }
    }

    /** Selects ids rather than COUNT(*) so the row count stays a Kotlin Int, not a java.lang.Long. */
    private fun countWhere(hql: String, param: UUID): Int = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s ->
            s.createQuery(hql, UUID::class.java).setParameter("id", param).resultList
        }
    }!!.size

    private fun capRows(capId: UUID) =
        countWhere("SELECT c.id FROM InterestCapitalizationEntity c WHERE c.id = :id", capId)

    private fun whtRows(capId: UUID) =
        countWhere("SELECT w.id FROM WithholdingTaxEntity w WHERE w.capitalizationId = :id", capId)

    private fun outboxRows(capId: UUID) =
        countWhere("SELECT o.eventId FROM InterestOutboxEntity o WHERE o.aggregateId = :id", capId)

    private fun accrualStatus(accrualId: UUID): AccrualStatus = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s ->
            s.createQuery("SELECT status FROM InterestAccrualEntity WHERE id = :id", AccrualStatus::class.java)
                .setParameter("id", accrualId).singleResult
        }
    }!!

    @Test
    fun `commits the capitalization, withholding, event and accrual flip together`() {
        val accountId = UUID.randomUUID()
        val accrualId = persistAccrual(accountId, "SAVINGS_CZK", AccrualStatus.ACCRUING)
        val cap = capitalizationOf(accountId)

        saveCapitalization(cap, listOf(accrualId))

        assertThat(capRows(cap.id)).isEqualTo(1)
        assertThat(whtRows(cap.id)).isEqualTo(1)
        assertThat(outboxRows(cap.id)).isEqualTo(1)
        assertThat(accrualStatus(accrualId)).isEqualTo(AccrualStatus.CAPITALIZED)
    }

    @Test
    fun `rolls back every row when the accrual status guard trips`() {
        val accountId = UUID.randomUUID()
        // Simulates the concurrent/retry case: this accrual was ALREADY capitalized, so the
        // status-guarded UPDATE matches 0 rows instead of 1 and the transaction must abort.
        val accrualId = persistAccrual(accountId, "SAVINGS_CZK", AccrualStatus.CAPITALIZED)
        val cap = capitalizationOf(accountId)

        assertThatThrownBy { saveCapitalization(cap, listOf(accrualId)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Capitalization aborted")
            .hasMessageContaining("expected to flip 1 ACCRUING accruals, matched 0")

        // THE point of this test: the failure left NOTHING behind. A committed capitalization here
        // would mean the customer was credited for interest whose accruals are still ACCRUING —
        // ready to be capitalized (and credited, and taxed) a second time.
        assertThat(capRows(cap.id)).isZero()
        assertThat(whtRows(cap.id)).isZero()
        assertThat(outboxRows(cap.id)).isZero()
    }

    @Test
    fun `V6 index rejects a second money-bearing capitalization for the same account, product and period`() {
        val accountId = UUID.randomUUID()
        val first = persistAccrual(accountId, "SAVINGS_CZK", AccrualStatus.ACCRUING)
        val second = persistAccrual(accountId, "SAVINGS_CZK", AccrualStatus.ACCRUING, LocalDate.of(2026, 1, 19))
        val cap1 = capitalizationOf(accountId)

        saveCapitalization(cap1, listOf(first))

        // Same (account, product, period_to) — a second credit for one period. Even if a caller
        // somehow bypassed the application guards, the DB must refuse this.
        val cap2 = capitalizationOf(accountId)
        assertThatThrownBy { saveCapitalization(cap2, listOf(second)) }
            .hasMessageContaining("uq_interest_capitalizations_period")

        assertThat(capRows(cap2.id)).isZero()
        assertThat(accrualStatus(second)).isEqualTo(AccrualStatus.ACCRUING)
    }
}
