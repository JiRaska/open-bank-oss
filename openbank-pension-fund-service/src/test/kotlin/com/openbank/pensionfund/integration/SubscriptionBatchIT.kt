// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "batch-operator", roles = ["ROLE_OPERATOR", "ROLE_API"])
class SubscriptionBatchIT {
    @Test
    fun `a closed second fund rejects every leg without a partial order`() {
        val contract = UUID.randomUUID()
        val first = fund()
        val second = fund()
        given().delete("/api/v1/funds/$second").then().statusCode(200)
        assertThat(place(contract, "closed-${UUID.randomUUID()}", first, second).statusCode).isEqualTo(409)
        assertThat(orderCount(contract)).isZero()
    }

    @Test
    fun `a second fund closing under a database lock cannot leave the first leg`() {
        val contract = UUID.randomUUID()
        val first = fund()
        val second = fund()
        val workers = Executors.newSingleThreadExecutor()
        connection().use { db ->
            db.autoCommit = false
            try {
                closeFundUnderLock(db, second)
                val attempt = workers.submit<Response> {
                    place(contract, "closing-${UUID.randomUUID()}", first, second)
                }
                Thread.sleep(150)
                assertThat(attempt.isDone).isFalse()
                db.commit()
                assertThat(attempt.get(15, TimeUnit.SECONDS).statusCode).isEqualTo(409)
                assertThat(orderCount(contract)).isZero()
            } finally {
                db.rollback()
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun `a different currency second fund rejects the whole batch`() {
        val contract = UUID.randomUUID()
        val first = fund()
        val second = fund("EUR")
        assertThat(place(contract, "currency-${UUID.randomUUID()}", first, second).statusCode).isEqualTo(409)
        assertThat(orderCount(contract)).isZero()
    }

    @Test
    fun `concurrent same batch key returns one receipt and changed allocation conflicts`() {
        val contract = UUID.randomUUID()
        val first = fund()
        val second = fund()
        val key = "batch-${UUID.randomUUID()}"
        val workers = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map { workers.submit<Response> { place(contract, key, first, second) } }
            val responses = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(responses.map { it.statusCode }).containsExactly(202, 202)
            assertThat(responses[0].body.asString()).isEqualTo(responses[1].body.asString())
            assertThat(orderCount(contract)).isEqualTo(2)
            assertThat(place(contract, key, second, first).statusCode).isEqualTo(409)
            assertThat(orderCount(contract)).isEqualTo(2)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `lost response replays the committed receipt after a target fund closes`() {
        val contract = UUID.randomUUID()
        val first = fund()
        val second = fund()
        val key = "timeout-${UUID.randomUUID()}"

        // The caller loses the response after commit and later retries its frozen request.
        val committed = place(contract, key, first, second)
        assertThat(committed.statusCode).isEqualTo(202)
        connection().use { db ->
            db.prepareStatement("UPDATE funds SET status = 'CLOSED' WHERE id = ?").use { statement ->
                statement.setObject(1, second)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }

        val replay = place(contract, key, first, second)
        assertThat(replay.statusCode).isEqualTo(202)
        assertThat(replay.body.asString()).isEqualTo(committed.body.asString())
        assertThat(orderCount(contract)).isEqualTo(2)
        assertThat(receiptCount(contract, key)).isEqualTo(1)
    }

    @Test
    fun `late leg key conflict rolls back the first insert and the batch receipt`() {
        val contract = UUID.randomUUID()
        val first = fund()
        val second = fund()
        val key = "collision-${UUID.randomUUID()}"
        given().contentType("application/json").header("Idempotency-Key", "$key:second")
            .body("""{"fundId":"$second","type":"SUBSCRIBE","amount":40.00}""")
            .post("/api/v1/contracts/$contract/orders").then().statusCode(202)
        assertThat(place(contract, key, first, second).statusCode).isEqualTo(409)
        assertThat(orderCount(contract)).isEqualTo(1)
        assertThat(receiptCount(contract, key)).isZero()
    }

    private fun place(contract: UUID, key: String, first: UUID, second: UUID): Response =
        given().contentType("application/json").header("Idempotency-Key", key)
            .body(
                """{"amount":100.00,"currency":"CZK","legs":[
                   {"fundId":"$first","amount":60.00,"idempotencyKey":"$key:first"},
                   {"fundId":"$second","amount":40.00,"idempotencyKey":"$key:second"}]}""",
            ).post("/api/v1/contracts/$contract/orders/subscriptions")

    private fun closeFundUnderLock(db: Connection, fundId: UUID) {
        db.prepareStatement("SELECT id FROM funds WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, fundId)
            statement.executeQuery().use { rows -> assertThat(rows.next()).isTrue() }
        }
        db.prepareStatement("UPDATE funds SET status = 'CLOSED' WHERE id = ?").use { statement ->
            statement.setObject(1, fundId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }

    private fun fund(currency: String = "CZK"): UUID {
        val isin = "CZ${UUID.randomUUID().toString().replace("-", "").take(9).uppercase()}0"
        val response = given().contentType("application/json")
            .body(
                """{"name":"Batch fund","isin":"$isin","lei":"315700ABCDEF12345678",
                   "depositaryReference":"DEP-BATCH","custodyAccountReference":"CUST-BATCH",
                   "currency":"$currency","riskClass":3,"mandatoryConservative":false,
                   "managementFeeRate":0.008,"launchNavPerUnit":1}""",
            ).post("/api/v1/funds").then().statusCode(201).extract().response()
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun orderCount(contract: UUID): Int = connection().use { db ->
        db.prepareStatement("SELECT count(*) FROM unit_orders WHERE contract_id = ?").use { statement ->
            statement.setObject(1, contract)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun receiptCount(contract: UUID, key: String): Int = connection().use { db ->
        db.prepareStatement("SELECT count(*) FROM subscription_order_batches WHERE contract_id = ? AND batch_key = ?")
            .use { statement ->
                statement.setObject(1, contract)
                statement.setString(2, key)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
