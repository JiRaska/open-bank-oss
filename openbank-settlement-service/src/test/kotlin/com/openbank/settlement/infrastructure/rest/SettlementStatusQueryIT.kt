// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * GET /api/v1/settlements/{id} over real HTTP against a real PostgreSQL: no mocked use case or
 * repository, so the row the operator sees is the row the saga wrote. Every domain state is seeded
 * directly, and the read must leave the row and its outbox untouched.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SettlementStatusQueryIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "settlement-query-operator", roles = ["ROLE_OPERATOR"])
    fun `operator reads every stored state with its exact amount and changes nothing`() {
        for (status in SettlementStatus.entries) {
            val id = seed(status)
            val before = snapshot(id)
            val response = given().get("$BASE/$id").then()
                .statusCode(200)
                .header("Cache-Control", "no-store")
                .extract()
            val body = response.asString()
            assertThat(response.path<String>("id")).isEqualTo(id.toString())
            assertThat(response.path<String>("payerAccountId")).isEqualTo(PAYER.toString())
            assertThat(response.path<String>("payeeAccountId")).isEqualTo(PAYEE.toString())
            assertThat(response.path<Any>("amount"))
                .describedAs("amount must be exact decimal text: %s", body)
                .isEqualTo("999999999999999.9900")
            assertThat(response.path<String>("currency")).isEqualTo("CZK")
            assertThat(response.path<String>("status"))
                .isEqualTo(SettlementResponseStatus.fromDomain(status).name)
            assertThat(response.path<Boolean>("recoveryRequired"))
                .isEqualTo(status == SettlementStatus.BALANCE_STATE_UNKNOWN)
            assertThat(snapshot(id)).describedAs("a read must not change state").isEqualTo(before)
        }
    }

    @Test
    @TestSecurity(user = "settlement-query-admin", roles = ["ROLE_ADMIN"])
    fun `administrator can read a stored settlement`() {
        given().get("$BASE/${seed(SettlementStatus.BOOKED)}").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "settlement-query-operator", roles = ["ROLE_OPERATOR"])
    fun `missing row and malformed id have distinct outcomes`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(404)
        given().get("$BASE/not-a-uuid").then().statusCode(400)
        given().get("$BASE/1-1-1-1-1").then().statusCode(400)
    }

    @Test
    fun `unauthenticated caller is refused`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "settlement-query-viewer", roles = ["ROLE_VIEWER"])
    fun `viewer role is refused`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "settlement-query-api", roles = ["ROLE_API"])
    fun `api role that may originate cannot read`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    fun `service account holding the operator role cannot borrow the human read`() {
        given().get("$BASE/${seed(SettlementStatus.BOOKED)}").then().statusCode(403)
    }

    private fun seed(status: SettlementStatus): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO settlements (id, payer_account_id, payee_account_id, amount, currency, status) " +
                    "VALUES (?, ?, ?, ?, 'CZK', ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, PAYER)
                statement.setObject(3, PAYEE)
                statement.setBigDecimal(4, AMOUNT)
                statement.setString(5, status.name)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun snapshot(id: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT row_to_json(s)::text || ':' || " +
                "(SELECT count(*) FROM settlement_outbox WHERE aggregate_id = s.id)::text " +
                "FROM settlements s WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private companion object {
        const val BASE = "/api/v1/settlements"
        val AMOUNT = BigDecimal("999999999999999.99")
        val PAYER: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000010")
        val PAYEE: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000011")
    }
}
