// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Exercises the real HTTP resource, persistence and outbox with test OIDC identities.
 * OIDC signature verification is supplied by Quarkus in production; no request field or header
 * is used as an identity assertion. Existing OPA grants remain a separate outer control.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionSourceOwnershipIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `domestic machine stores source and replays without mutation or a second event`() {
        val key = UUID.randomUUID().toString()
        val paymentId = UUID.randomUUID()
        val created = post(key, paymentId)
        assertThat(created.statusCode).isEqualTo(201)
        val transactionId = UUID.fromString(created.jsonPath().getString("id"))
        val before = stored(transactionId)
        assertThat(before.paymentId).isEqualTo(paymentId.toString())
        assertThat(before.initiatedPaymentId).isEqualTo(paymentId.toString())

        for (source in listOf(paymentId, null)) {
            val replay = post(key, source)
            assertThat(replay.statusCode).isEqualTo(201)
            assertThat(replay.jsonPath().getString("id")).isEqualTo(transactionId.toString())
            assertThat(stored(transactionId)).isEqualTo(before)
        }
        assertThat(post(key, UUID.randomUUID()).statusCode).isEqualTo(409)
        assertThat(stored(transactionId)).isEqualTo(before)
    }

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `legacy null source remains null when a newly upgraded producer retries`() {
        val key = UUID.randomUUID().toString()
        val created = post(key, null)
        assertThat(created.statusCode).isEqualTo(201)
        val id = UUID.fromString(created.jsonPath().getString("id"))
        val before = stored(id)
        assertThat(before.paymentId).isNull()
        val replay = post(key, UUID.randomUUID())
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.jsonPath().getString("id")).isEqualTo(id.toString())
        assertThat(stored(id)).isEqualTo(before)
    }

    @Test
    @TestSecurity(user = "operator-test", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `operator cannot forge domestic source using body rail client id or identity headers`() {
        assertDenied(given().header("X-Service-Identity", DOMESTIC_PRINCIPAL))
    }

    @Test
    @TestSecurity(user = "service-account-other-payment", roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `matching authorized party cannot replace the named machine principal`() {
        assertDenied()
    }

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `racing different payment sources books only the winner and rejects the loser`() {
        val key = UUID.randomUUID().toString()
        val payments = listOf(UUID.randomUUID(), UUID.randomUUID())
        val barrier = CyclicBarrier(payments.size)
        val testClassLoader = Thread.currentThread().contextClassLoader
        val futures = payments.map { paymentId ->
            CompletableFuture.supplyAsync {
                val thread = Thread.currentThread()
                val originalClassLoader = thread.contextClassLoader
                thread.contextClassLoader = testClassLoader
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                    paymentId to post(key, paymentId)
                } finally {
                    thread.contextClassLoader = originalClassLoader
                }
            }
        }
        val responses = futures.map { it.get(60, TimeUnit.SECONDS) }
        assertThat(responses.map { it.second.statusCode })
            .describedAs("parallel source claim responses: %s", responses.map { it.second.body.asString() })
            .containsExactlyInAnyOrder(201, 409)
        val (paymentId, winner) = responses.single { it.second.statusCode == 201 }
        val id = UUID.fromString(winner.jsonPath().getString("id"))
        val row = stored(id)
        assertThat(rowCount(key)).isEqualTo(1)
        assertThat(row.paymentId).isEqualTo(paymentId.toString())
        assertThat(row.initiatedPaymentId).isEqualTo(paymentId.toString())
        // One initial write and one terminal write: the losing request creates neither a second
        // durable initiation nor another terminal transition.
        assertThat(row.version).isEqualTo(1)
        assertThat(row.events).isEqualTo(2)
        assertThat(initiationCount(payments)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "other-payment")])
    fun `matching named machine principal cannot replace the authorized party`() {
        assertDenied()
    }

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_API"])
    fun `named principal and role without verified token claims cannot attach a source`() {
        assertDenied()
    }

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `named machine token without API role cannot attach a source`() {
        assertDenied()
    }

    @Test
    @TestSecurity(user = DOMESTIC_PRINCIPAL, roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = "openbank-domestic-payment")])
    fun `domestic source requires a domestic debit and a source account`() {
        val invalidShapes = listOf(
            mapOf("rail" to "SEPA_CT"),
            mapOf("type" to "CREDIT"),
            mapOf("sourceAccountId" to null),
        )
        for (changes in invalidShapes) {
            val key = UUID.randomUUID().toString()
            val request = payload(key, UUID.randomUUID()) + changes
            assertThat(given().contentType("application/json").body(request).post(PATH).statusCode).isEqualTo(403)
            assertThat(rowCount(key)).isZero()
        }
    }

    @Test
    @TestSecurity(user = "operator-test", roles = ["ROLE_OPERATOR"])
    fun `omitting source retains the existing operator booking contract`() {
        val key = UUID.randomUUID().toString()
        val response = given().contentType("application/json")
            .body(payload(key, null) - "originatingPaymentId").post(PATH)
        assertThat(response.statusCode).isEqualTo(201)
    }

    private fun assertDenied(request: io.restassured.specification.RequestSpecification = given()) {
        val key = UUID.randomUUID().toString()
        val body = payload(key, UUID.randomUUID()) + mapOf("clientId" to "openbank-domestic-payment")
        assertThat(request.contentType("application/json").body(body).post(PATH).statusCode).isEqualTo(403)
        assertThat(rowCount(key)).isZero()
    }

    private fun post(key: String, paymentId: UUID?): Response =
        given().contentType("application/json").body(payload(key, paymentId)).post(PATH)

    private fun payload(key: String, paymentId: UUID?): Map<String, Any?> = mapOf(
        "idempotencyKey" to key,
        "type" to "DEBIT",
        "rail" to "DOMESTIC",
        "instructionType" to "ONE_OFF",
        "sourceAccountId" to UUID.randomUUID(),
        "targetAccountId" to UUID.randomUUID(),
        "amount" to "12.34",
        "currencyCode" to "CZK",
        "valueDate" to LocalDate.now().toString(),
        "originatingPaymentId" to paymentId,
    )

    private data class StoredSource(
        val paymentId: String?,
        val version: Long,
        val events: Int,
        val initiatedPaymentId: String?,
    )

    private fun stored(id: UUID): StoredSource = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT t.originating_payment_id::text, t.version,
                   (SELECT count(*) FROM transaction_outbox o WHERE o.aggregate_id = t.id),
                   (SELECT payload::jsonb ->> 'originatingPaymentId' FROM transaction_outbox o
                    WHERE o.aggregate_id = t.id
                    AND o.event_type = 'openbank.transactions.transaction.initiated')
            FROM transactions t WHERE t.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Created transaction was not persisted" }
                StoredSource(rows.getString(1), rows.getLong(2), rows.getInt(3), rows.getString(4))
            }
        }
    }

    private fun rowCount(key: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM transactions WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun initiationCount(payments: List<UUID>): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT count(*) FROM transaction_outbox
            WHERE event_type = 'openbank.transactions.transaction.initiated'
              AND payload::jsonb ->> 'originatingPaymentId' IN (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, payments[0].toString())
            statement.setString(2, payments[1].toString())
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private companion object {
        const val DOMESTIC_PRINCIPAL = "service-account-openbank-domestic-payment"
        const val PATH = "/api/v1/transactions"
    }
}
