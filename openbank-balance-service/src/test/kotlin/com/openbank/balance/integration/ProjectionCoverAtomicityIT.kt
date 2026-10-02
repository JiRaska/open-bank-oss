// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.integration

import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@QuarkusTestResource(BalanceOutboxWriteIT.DispatcherOffResource::class)
@TestSecurity(user = "projection-probe", roles = ["ROLE_API"])
class ProjectionCoverAtomicityIT {
    @Inject lateinit var projection: LedgerProjectionUseCase

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `payee event arriving first cannot release payer cover`() {
        val payer = initialize()
        val payee = initialize()
        val transaction = UUID.randomUUID()
        reserve(payer, transaction)
        apply(change(payee, transaction, "100"))
        assertPocket(payer, "1000", "900", "100")
        assertPocket(payee, "1100", "1100", "0")
        val debit = change(payer, transaction, "-100")
        apply(debit)
        apply(debit)
        assertPocket(payer, "900", "900", "0")
    }

    @Test
    fun `release event failure rolls back projection and leaves cover intact`() {
        val payer = initialize()
        val transaction = UUID.randomUUID()
        reserve(payer, transaction)
        val debit = change(payer, transaction, "-100")
        val constraint = "probe_${payer.toString().replace("-", "") }"
        sql(
            "ALTER TABLE balance_outbox ADD CONSTRAINT $constraint " +
                "CHECK (aggregate_id <> '$payer' OR event_type <> 'HOLD_RELEASED') NOT VALID",
        )
        try {
            assertThatThrownBy { apply(debit) }.isInstanceOf(Exception::class.java)
            assertPocket(payer, "1000", "900", "100")
            assertThat(markerCount(payer)).isZero()
        } finally {
            sql("ALTER TABLE balance_outbox DROP CONSTRAINT $constraint")
        }
        apply(debit)
        assertPocket(payer, "900", "900", "0")
        assertThat(markerCount(payer)).isEqualTo(1)
    }

    @Test
    fun `duplicate projection repairs legacy unreleased cover without applying money twice`() {
        val payer = initialize()
        val transaction = UUID.randomUUID()
        reserve(payer, transaction)
        val debit = change(payer, transaction, "-100")
        apply(debit)
        // Recreate the old two-transaction failure: booked delta committed, cover release lost.
        sql("UPDATE balance_holds SET released_at = NULL WHERE account_id = '$payer'")
        sql("UPDATE balances SET reserved_amount = 100, available_amount = 800 WHERE account_id = '$payer'")
        apply(debit)
        assertPocket(payer, "900", "900", "0")
        assertThat(markerCount(payer)).isEqualTo(1)
        apply(debit)
        assertPocket(payer, "900", "900", "0")
    }

    @Test
    fun `release after projection cannot consume another payments reservation`() {
        val payer = initialize()
        val transaction = UUID.randomUUID()
        val consumed = reserve(payer, transaction)
        reserve(payer, UUID.randomUUID())
        apply(change(payer, transaction, "-100"))
        assertPocket(payer, "900", "800", "100")
        given().delete("/api/v1/balances/holds/$consumed").then().statusCode(200)
        assertPocket(payer, "900", "800", "100")
        assertThat(releaseEventCount(payer)).isEqualTo(1)
    }

    private fun initialize(): UUID = UUID.randomUUID().also { account ->
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "1000"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
    }

    private fun reserve(account: UUID, transaction: UUID): String = given().contentType("application/json")
        .body(
            mapOf(
                "amount" to "100",
                "currency" to "CZK",
                "reason" to "settlement cover",
                "referenceId" to "$transaction",
            ),
        )
        .post("/api/v1/balances/$account/holds").then().statusCode(201).extract().path("id")

    private fun change(account: UUID, transaction: UUID, delta: String) = AccountBookedChange(
        account,
        "CZK",
        BigDecimal(delta),
        UUID.randomUUID(),
        transaction,
        LocalDate.of(2026, 1, 1),
        1,
    )

    private fun apply(change: AccountBookedChange): Unit = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { projection.apply(change) }.asUni()
    }

    private fun assertPocket(account: UUID, booked: String, available: String, reserved: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT booked_amount, available_amount, reserved_amount FROM balances WHERE account_id = ? AND currency = 'CZK'",
            ).use { query ->
                query.setObject(1, account)
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(booked)
                    assertThat(rows.getBigDecimal(2)).isEqualByComparingTo(available)
                    assertThat(rows.getBigDecimal(3)).isEqualByComparingTo(reserved)
                }
            }
        }
    }

    private fun markerCount(account: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM ledger_projection_event WHERE account_id = ?").use { query ->
            query.setObject(1, account)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun releaseEventCount(account: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM balance_outbox WHERE aggregate_id = ? AND event_type = 'HOLD_RELEASED'",
        ).use { query ->
            query.setObject(1, account)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun sql(statement: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(statement) } }
    }
}
