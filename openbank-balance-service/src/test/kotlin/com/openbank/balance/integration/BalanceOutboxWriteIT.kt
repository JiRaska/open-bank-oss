// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8510 (the balance half): `balance_outbox` shipped complete — migration, dispatcher,
 * backlog gauge, `dispatch-enabled: true`, an atomic claimProcessable — and NOTHING ever wrote to
 * it. Every balance event went out through `KafkaBalanceEventPublisher`, a bare
 * `@Channel("balance-events-out")` emitter called after the repository transaction had already
 * committed — a dual write that could lose the event after the commit or announce a mutation that
 * rolled back. (#4007 mis-binned balance into "write side exists" on a count that included the
 * port DECLARATION of `persistInTransaction`; measured, the only hit was that declaration.)
 *
 * Only a real-DB integration test can prove the fix. A unit test that mocks the repository cannot
 * tell which publisher a use case called — that is exactly why the defect survived a fully green
 * suite for the life of the service. And the repository cannot be called directly either: a
 * `Panache.withTransaction` reactive repo invoked from a bare `@QuarkusTest` thread throws
 * "No current Vertx context found"; only a real HTTP request carries a Vert.x context. So this
 * drives the REST endpoints with RestAssured and reads the row back over plain JDBC — the
 * `PartyOutboxWriteIT` / `LendingOutboxWriteIT` pattern.
 *
 * The dispatcher is switched off for the duration so it cannot mark a row SENT (or drain it)
 * before the assertion observes it — the claim under test is that the row is WRITTEN in the
 * state-change transaction, not what happens to it afterwards.
 *
 * Red against unmodified main: REST 200, zero balance_outbox rows for the account.
 */
@QuarkusTest
@QuarkusTestResource(BalanceOutboxWriteIT.DispatcherOffResource::class)
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BalanceOutboxWriteIT {

    class DispatcherOffResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    companion object {
        private val accountId: UUID = UUID.randomUUID()
    }

    private fun outboxRows(): List<Triple<String, String, String>> = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement(
            "SELECT event_type, aggregate_id, status FROM balance_outbox " +
                "WHERE aggregate_id = ? ORDER BY created_at ASC",
        )
        ps.setObject(1, accountId)
        val rs = ps.executeQuery()
        val rows = mutableListOf<Triple<String, String, String>>()
        while (rs.next()) {
            rows += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
        }
        rows
    }

    @Test
    @Order(1)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `credit writes the BALANCE_UPDATED row in the movement transaction`() {
        Given {
            contentType("application/json")
            body("""{"currency": "CZK"}""")
        } When {
            post("/api/v1/balances/$accountId/initialize")
        } Then {
            statusCode(201)
        }

        Given {
            contentType("application/json")
            body("""{"amount": "250.00", "currency": "CZK", "referenceId": "outbox-probe-credit-1"}""")
        } When {
            post("/api/v1/balances/$accountId/credit")
        } Then {
            statusCode(200)
        }

        val rows = outboxRows()
        assertThat(rows)
            .describedAs("the credit must write exactly one BALANCE_UPDATED row to balance_outbox (#8510)")
            .hasSize(1)
        assertThat(rows.single().first).isEqualTo("BALANCE_UPDATED")
        assertThat(rows.single().second).isEqualTo(accountId.toString())
        assertThat(rows.single().third).isEqualTo("PENDING")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `a duplicate credit writes no second outbox row`() {
        Given {
            contentType("application/json")
            body("""{"amount": "250.00", "currency": "CZK", "referenceId": "outbox-probe-credit-1"}""")
        } When {
            post("/api/v1/balances/$accountId/credit")
        } Then {
            statusCode(200)
        }

        // Same referenceId as Order(1): the dedup hit must not announce a second BALANCE_UPDATED.
        assertThat(outboxRows()).hasSize(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `placeHold writes the HOLD_PLACED row in the same transaction as the reservation`() {
        Given {
            contentType("application/json")
            body(
                """{"amount": "100.00", "currency": "CZK", "reason": "outbox probe", "referenceId": "outbox-probe-hold-1"}""",
            )
        } When {
            post("/api/v1/balances/$accountId/holds")
        } Then {
            statusCode(201)
        }

        val rows = outboxRows()
        assertThat(rows.map { it.first }).containsExactly("BALANCE_UPDATED", "HOLD_PLACED")
    }

    /**
     * #8353 — presence is not atomicity. The tests above prove a row is WRITTEN; an implementation
     * that committed the movement in one transaction and the event in a second would satisfy all
     * of them. Postgres stamps each row version with `xmin`, the id of the transaction that wrote
     * it, so rows written together share it and rows written apart cannot.
     */
    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `the movement, the hold and their outbox rows carry their writing transaction's xmin`() {
        val movement = xmin("SELECT xmin::text FROM balance_movement WHERE account_id = ? AND reference_id = ?") {
            it.setObject(1, accountId)
            it.setString(2, "outbox-probe-credit-1")
        }
        val hold = xmin("SELECT xmin::text FROM balance_holds WHERE account_id = ? AND reference_id = ?") {
            it.setObject(1, accountId)
            it.setString(2, "outbox-probe-hold-1")
        }
        val updated = outboxXmin("BALANCE_UPDATED")
        val placed = outboxXmin("HOLD_PLACED")

        assertThat(movement).describedAs("the credit's balance_movement row").hasSize(1)
        assertThat(hold).describedAs("the balance_holds row").hasSize(1)
        assertThat(updated).hasSize(1)
        assertThat(placed).hasSize(1)

        assertThat(updated.single())
            .describedAs("the movement row and BALANCE_UPDATED must be written by ONE transaction")
            .isEqualTo(movement.single())
        assertThat(placed.single())
            .describedAs("the hold row and HOLD_PLACED must be written by ONE transaction")
            .isEqualTo(hold.single())
        // Known-different control: the credit and the hold were two requests, so two transactions.
        // The same comparison must therefore FAIL across them, or the matches above match anything.
        assertThat(placed.single())
            .describedAs("control: the credit and the hold cannot share a writing transaction")
            .isNotEqualTo(updated.single())
        // And the probe can return nothing: an event type never written yields no xmin.
        assertThat(outboxXmin("NEVER_WRITTEN")).isEmpty()
    }

    private fun outboxXmin(eventType: String): List<String> =
        xmin("SELECT xmin::text FROM balance_outbox WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at") {
            it.setObject(1, accountId)
            it.setString(2, eventType)
        }

    private fun xmin(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<String> =
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement(sql)
            bind(ps)
            val rs = ps.executeQuery()
            val out = mutableListOf<String>()
            while (rs.next()) out += rs.getString(1)
            out
        }
}
