// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP and Postgres: expired does not mean released, and a read must move no funds. */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
class BalanceActiveHoldsIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "holds-reader", roles = ["ROLE_VIEWER"])
    fun `active holds omit expired unreleased released and other account rows without releasing funds`() {
        val account = UUID.randomUUID()
        val active = UUID.randomUUID()
        val noExpiry = UUID.randomUUID()
        val expired = UUID.randomUUID()
        val released = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO balances(id,account_id,currency,booked_amount,available_amount,reserved_amount) " +
                    "VALUES (?, ?, 'CZK', 1000, 970, 30)",
            ).use { ps ->
                // JDBC seeds use negative ids so BIGSERIAL cannot collide with Hibernate pooled ids.
                ps.setLong(1, account.mostSignificantBits or Long.MIN_VALUE)
                ps.setObject(2, account)
                ps.executeUpdate()
            }
            fun seed(id: UUID, owner: UUID, expiry: String, release: String) {
                conn.prepareStatement(
                    "INSERT INTO balance_holds(id,hold_id,account_id,amount,currency,reason,reference_id," +
                        "expires_at,released_at) VALUES (?, ?, ?, 10, 'CZK', 'active-read-fixture', ?, $expiry, $release)",
                ).use { ps ->
                    ps.setLong(1, id.mostSignificantBits or Long.MIN_VALUE)
                    ps.setObject(2, id)
                    ps.setObject(3, owner)
                    ps.setString(4, id.toString())
                    ps.executeUpdate()
                }
            }
            seed(active, account, "NOW() + INTERVAL '1 day'", "NULL")
            seed(noExpiry, account, "NULL", "NULL")
            seed(expired, account, "NOW() - INTERVAL '1 day'", "NULL")
            seed(released, account, "NOW() + INTERVAL '1 day'", "NOW()")
            seed(UUID.randomUUID(), UUID.randomUUID(), "NULL", "NULL")
        }
        val before = snapshot(account)
        val response = (
            Given { this } When {
                get("/api/v1/balances/$account/holds")
            } Then {
                statusCode(200)
            }
            ).extract()
        assertThat(response.jsonPath().getList<String>("holds.id"))
            .containsExactlyInAnyOrder(active.toString(), noExpiry.toString())
        assertThat(snapshot(account)).isEqualTo(before)
    }

    @Test
    fun `GET holds without identity returns 401`() {
        Given { this } When { get("/api/v1/balances/${UUID.randomUUID()}/holds") } Then { statusCode(401) }
    }

    @Test
    @TestSecurity(user = "wrong-role", roles = ["ROLE_UNRELATED"])
    fun `GET holds with an unrelated role returns 403`() {
        Given { this } When { get("/api/v1/balances/${UUID.randomUUID()}/holds") } Then { statusCode(403) }
    }

    private fun snapshot(account: UUID): List<String> = dataSource.connection.use { conn ->
        listOf(
            "SELECT row_to_json(b)::text FROM balances b WHERE account_id = ? ORDER BY id",
            "SELECT row_to_json(h)::text FROM balance_holds h WHERE account_id = ? ORDER BY id",
            "SELECT row_to_json(o)::text FROM balance_outbox o WHERE aggregate_id = ? ORDER BY created_at",
        ).flatMap { sql -> snapshotRows(conn, sql, account) }
    }

    private fun snapshotRows(conn: Connection, sql: String, account: UUID): List<String> =
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, account)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
}
