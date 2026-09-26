// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * #10281: an EDIT of a standing order is `create(replacesStandingOrderId)`, never create-then-cancel.
 * Measured on the table: after a replace exactly ONE order of the party is ACTIVE, both rows were
 * written by ONE transaction (same `xmin`, see [StandingOrderOutboxAtomicityIT] for why that is the
 * falsifiable property), a replay changes nothing, and a refused replace leaves the original as it
 * was and creates nothing.
 */
@QuarkusTest
@QuarkusTestResource(StandingOrderReplaceIT.NoDispatchResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class StandingOrderReplaceIT {

    class NoDispatchResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("standing-order-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("standing-order-due-in") +
                mapOf(
                    "openbank.outbox.dispatch-enabled" to "false",
                    "openbank.scheduler.execution-enabled" to "false",
                )

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    private val party = UUID.randomUUID()

    @Test
    fun `release of an edit leaves exactly one active order, written in one transaction`() {
        val old = id(post(body("old-${UUID.randomUUID()}", AMOUNT_OLD)))
        val replacement = post(body("edit-${UUID.randomUUID()}", AMOUNT_NEW, replaces = old))

        assertThat(replacement.statusCode).describedAs(replacement.body.asString()).isEqualTo(HTTP_CREATED)
        val new = id(replacement)
        assertThat(status(old)).isEqualTo("CANCELLED")
        assertThat(status(new)).isEqualTo("ACTIVE")
        assertThat(activeCount()).isEqualTo(1)
        assertThat(xmin(old)).describedAs("cancel and create in one transaction").isEqualTo(xmin(new))
    }

    @Test
    fun `a replayed edit is idempotent - no second order, nothing cancelled twice`() {
        val old = id(post(body("old-${UUID.randomUUID()}", AMOUNT_OLD)))
        val key = "edit-${UUID.randomUUID()}"
        val first = id(post(body(key, AMOUNT_NEW, replaces = old)))

        val replay = post(body(key, AMOUNT_NEW, replaces = old))

        assertThat(replay.statusCode).isEqualTo(HTTP_CREATED)
        assertThat(id(replay)).isEqualTo(first)
        assertThat(activeCount()).isEqualTo(1)
        assertThat(rowCount()).isEqualTo(2)
    }

    @Test
    fun `an edit of an order that is no longer active changes nothing and creates nothing`() {
        val old = id(post(body("old-${UUID.randomUUID()}", AMOUNT_OLD)))
        RestAssured.given().delete("/api/v1/standing-orders/$old").then().statusCode(HTTP_OK_OR_NO_CONTENT)

        val refused = post(body("edit-${UUID.randomUUID()}", AMOUNT_NEW, replaces = old))

        assertThat(refused.statusCode).isEqualTo(HTTP_UNPROCESSABLE)
        assertThat(rowCount()).isEqualTo(1)
        assertThat(activeCount()).isZero()
    }

    @Test
    fun `an edit naming another party's order changes nothing`() {
        val foreign = id(post(body("foreign-${UUID.randomUUID()}", AMOUNT_OLD, partyId = UUID.randomUUID())))

        val refused = post(body("edit-${UUID.randomUUID()}", AMOUNT_NEW, replaces = foreign))

        assertThat(refused.statusCode).isEqualTo(HTTP_UNPROCESSABLE)
        assertThat(status(foreign)).isEqualTo("ACTIVE")
        assertThat(rowCount()).isZero()
    }

    private fun body(key: String, amount: Long, replaces: UUID? = null, partyId: UUID = party) = """
        {
          "idempotencyKey": "$key",
          "partyId": "$partyId",
          "debitAccountId": "$ACCOUNT",
          "creditorIban": "CZ6508000000192000145399",
          "creditorName": "Pronajimatel",
          "creditorBic": null,
          "amountMinorUnits": $amount,
          "currency": "CZK",
          "frequency": "MONTHLY",
          "paymentType": "DOMESTIC",
          "remittanceInfo": null,
          "startDate": "${LocalDate.now().plusDays(1)}",
          "endDate": null${replaces?.let { ",\n  \"replacesStandingOrderId\": \"$it\"" }.orEmpty()}
        }
    """.trimIndent()

    private fun post(json: String): Response =
        RestAssured.given().contentType("application/json").body(json).post("/api/v1/standing-orders")

    private fun id(r: Response): UUID {
        assertThat(r.statusCode).describedAs(r.body.asString()).isEqualTo(HTTP_CREATED)
        return UUID.fromString(r.jsonPath().getString("id"))
    }

    private fun status(id: UUID): String = single("select status from standing_orders where id = ?", id)

    private fun xmin(id: UUID): String = single("select xmin::text from standing_orders where id = ?", id)

    private fun activeCount(): Int =
        single("select count(*)::text from standing_orders where party_id = ? and status = 'ACTIVE'", party).toInt()

    private fun rowCount(): Int = single("select count(*)::text from standing_orders where party_id = ?", party).toInt()

    private fun single(sql: String, arg: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, arg)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "no row for $sql" }
                rs.getString(1)
            }
        }
    }

    private companion object {
        val ACCOUNT: UUID = UUID.randomUUID()
        const val AMOUNT_OLD = 150_000L
        const val AMOUNT_NEW = 175_000L
        const val HTTP_CREATED = 201
        const val HTTP_UNPROCESSABLE = 422
        val HTTP_OK_OR_NO_CONTENT = org.hamcrest.Matchers.isOneOf(200, 204)
    }
}
