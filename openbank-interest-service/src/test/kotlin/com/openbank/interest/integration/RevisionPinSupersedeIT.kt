// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.integration

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.ManageRateConfigUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.CatalogSourcedRateException
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.infrastructure.catalog.CatalogInterestEventResolution
import com.openbank.interest.infrastructure.catalog.CatalogInterestProfile
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncOutcome
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncRepository
import com.openbank.interest.infrastructure.client.CatalogEventClientResponse
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0341 against the real schema: a superseding catalog revision reprices NEW accounts only. An
 * account pinned to revision A keeps A's rate after revision B is synced and effective.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
internal class RevisionPinSupersedeIT {
    @Inject lateinit var sync: CatalogInterestSyncRepository

    @Inject lateinit var accrue: AccrueInterestUseCase

    @Inject lateinit var rates: ManageRateConfigUseCase

    @Inject lateinit var dataSource: DataSource

    private val specification = UUID.randomUUID()

    private fun publish(rate: String, from: LocalDate, cursor: String): UUID {
        val revisionId = UUID.randomUUID()
        val profile = CatalogInterestProfile(
            revisionId = revisionId, offeringId = UUID.randomUUID(), specificationId = specification,
            schemaId = "org.openbank.banking.deposit", schemaVersion = 2, contentHash = "b".repeat(64),
            currency = "EUR", annualRate = BigDecimal(rate), dayCount = DayCount.ACT_365,
            effectiveFrom = from, effectiveTo = null,
        )
        val event = CatalogEventClientResponse(
            id = UUID.randomUUID(),
            aggregateType = "catalog.revision",
            eventType = "com.openbank.catalog.revision_published",
            aggregateId = revisionId,
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC),
        )
        val outcome = onEventLoop { sync.record(CatalogInterestEventResolution.applied(event, profile), cursor) }
        assertThat(outcome).isEqualTo(CatalogInterestSyncOutcome.APPLIED)
        return revisionId
    }

    private fun accrueOn(account: UUID, date: LocalDate) = onEventLoop {
        accrue.accrue(AccrualRequest(account, specification.toString(), BigDecimal("1000.00"), "EUR", date))
    }

    @Test
    fun `superseded revision does not change an existing account's rate but prices a new account`() {
        publish("0.020000000000000000", LocalDate.of(2027, 1, 1), "c-a")
        val existing = UUID.randomUUID()
        val first = accrueOn(existing, LocalDate.of(2027, 1, 15))

        publish("0.030000000000000000", LocalDate.of(2027, 2, 1), "c-b")
        val later = accrueOn(existing, LocalDate.of(2027, 2, 10))
        val brandNew = accrueOn(UUID.randomUUID(), LocalDate.of(2027, 2, 10))

        // 1000 * 0.02 / 365 and 1000 * 0.03 / 365, at the accrual's 6-digit scale.
        assertThat(later.configId).isEqualTo(first.configId)
        assertThat(later.accruedAmount).isEqualByComparingTo("0.054795")
        assertThat(brandNew.configId).isNotEqualTo(first.configId)
        assertThat(brandNew.accruedAmount).isEqualByComparingTo("0.082192")
    }

    @Test
    fun `account with accrual history and no pin is backfilled to the config it last accrued under`() {
        publish("0.020000000000000000", LocalDate.of(2027, 4, 1), "c-d")
        val legacy = UUID.randomUUID()
        val first = accrueOn(legacy, LocalDate.of(2027, 4, 5))
        // Simulate an account that accrued BEFORE V19: drop its pin, keep its accrual history.
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM account_rate_revision_pins WHERE account_id = ?").use {
                it.setObject(1, legacy)
                it.executeUpdate()
            }
        }
        // Run the V20 backfill exactly as shipped.
        val sql = javaClass.classLoader.getResourceAsStream(
            "db/migration/V20__backfill_account_rate_revision_pins.sql",
        )!!
            .bufferedReader().readText()
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        publish("0.030000000000000000", LocalDate.of(2027, 4, 10), "c-e")
        val next = accrueOn(legacy, LocalDate.of(2027, 4, 15))

        assertThat(next.configId).isEqualTo(first.configId)
        assertThat(next.accruedAmount).isEqualByComparingTo("0.054795")
    }

    @Test
    fun `account whose history is only operator-config is pinned at its first catalog accrual`() {
        val legacy = UUID.randomUUID()
        // History under an operator-managed config: seed one directly for this specification.
        val operatorConfig = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO interest_rate_configs (id, product_id, currency, annual_rate, day_count, " +
                    "effective_from, effective_to, active, created_at, updated_at) " +
                    "VALUES (?, ?, 'EUR', 0.010000, 'ACT_365', " +
                    "DATE '2027-04-01', DATE '2027-04-04', true, NOW(), NOW())",
            ).use {
                it.setObject(1, operatorConfig)
                it.setString(2, specification.toString())
                it.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO interest_accruals (id, account_id, product_id, config_id, accrual_date, balance, " +
                    "daily_rate, accrued_amount, currency) VALUES (gen_random_uuid(), ?, ?, ?, DATE '2027-04-02', " +
                    "1000, 0.0000273973, 0.027397, 'EUR')",
            ).use {
                it.setObject(1, legacy)
                it.setString(2, specification.toString())
                it.setObject(3, operatorConfig)
                it.executeUpdate()
            }
        }
        publish("0.020000000000000000", LocalDate.of(2027, 4, 5), "c-f")
        val first = accrueOn(legacy, LocalDate.of(2027, 4, 20))
        publish("0.030000000000000000", LocalDate.of(2027, 4, 25), "c-g")
        val later = accrueOn(legacy, LocalDate.of(2027, 4, 30))

        assertThat(later.configId).isEqualTo(first.configId)
        assertThat(later.accruedAmount).isEqualByComparingTo("0.054795")
    }

    @Test
    fun `account whose latest accrual is operator-managed keeps its earlier catalog pin`() {
        publish("0.020000000000000000", LocalDate.of(2027, 5, 1), "c-h")
        val legacy = UUID.randomUUID()
        val first = accrueOn(legacy, LocalDate.of(2027, 5, 5))
        val operatorConfig = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM account_rate_revision_pins WHERE account_id = ?").use {
                it.setObject(1, legacy)
                it.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO interest_rate_configs (id, product_id, currency, annual_rate, day_count, " +
                    "effective_from, effective_to, active, created_at, updated_at) " +
                    "VALUES (?, ?, 'EUR', 0.010000, 'ACT_365', DATE '2026-01-01', DATE '2026-01-02', " +
                    "true, NOW(), NOW())",
            ).use {
                it.setObject(1, operatorConfig)
                it.setString(2, specification.toString())
                it.executeUpdate()
            }
            // The LATEST accrual ran under the operator config; the earlier one under the catalog config.
            c.prepareStatement(
                "INSERT INTO interest_accruals (id, account_id, product_id, config_id, accrual_date, balance, " +
                    "daily_rate, accrued_amount, currency) VALUES (gen_random_uuid(), ?, ?, ?, DATE '2027-05-06', " +
                    "1000, 0.0000273973, 0.027397, 'EUR')",
            ).use {
                it.setObject(1, legacy)
                it.setString(2, specification.toString())
                it.setObject(3, operatorConfig)
                it.executeUpdate()
            }
        }
        val script = "db/migration/V20__backfill_account_rate_revision_pins.sql"
        val sql = javaClass.classLoader.getResourceAsStream(script)!!.bufferedReader().readText()
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        publish("0.030000000000000000", LocalDate.of(2027, 5, 10), "c-i")
        val next = accrueOn(legacy, LocalDate.of(2027, 5, 15))

        assertThat(next.configId).isEqualTo(first.configId)
    }

    @Test
    fun `admin API refuses to deactivate a catalog-sourced rate row`() {
        publish("0.020000000000000000", LocalDate.of(2027, 3, 1), "c-c")
        val cached = onEventLoop { rates.listConfigs(specification.toString()) }.first()

        val failure = runCatching { onEventLoop { rates.deactivateConfig(cached.id) } }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogSourcedRateException::class.java)
        assertThat(onEventLoop { rates.getConfig(cached.id) }!!.active).isTrue()
    }

    private fun <T> onEventLoop(work: () -> Uni<T>): T = VertxContextSupport.subscribeAndAwait(work)
}
