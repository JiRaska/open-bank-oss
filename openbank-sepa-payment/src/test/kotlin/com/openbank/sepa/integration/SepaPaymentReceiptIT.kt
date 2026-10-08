// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.openbank.libs.idempotency.IdempotencyScope
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(SepaPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SepaPaymentReceiptIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var redis: ReactiveRedisDataSource

    @Test
    @Order(1)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `creator can resolve only the original request`() {
        val created = create()
        assertThat(created.statusCode).isEqualTo(201)
        paymentId = created.jsonPath().getString("id")
        assertThat(receipt().jsonPath().getString("paymentId")).isEqualTo(paymentId)
        assertThat(receipt(account = UUID.randomUUID()).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(receipt(key = UUID.randomUUID().toString()).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sepa_payments SET debtor_name = 'Updated Name', debtor_iban = 'DE89370400440532013000' " +
                    "WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, idempotencyKey)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(receipt().jsonPath().getString("paymentId")).isEqualTo(paymentId)
        evictCreatorRedisKey()
        assertThat(create().jsonPath().getString("id")).isEqualTo(paymentId)
        assertThat(rows()).isEqualTo(1)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "receipt-operator-b", roles = ["ROLE_PAYMENTS"])
    fun `another principal cannot resolve or replay the globally unique key`() {
        assertThat(receipt().jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(
            receipt(party = UUID.randomUUID(), actor = UUID.randomUUID()).jsonPath().getString("state"),
        ).isEqualTo("UNKNOWN")
        assertThat(create().statusCode).isEqualTo(409)
        assertThat(rows()).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `legacy row without durable provenance stays unknown and cannot replay`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sepa_payments SET initiating_principal = NULL WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, idempotencyKey)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(receipt().jsonPath().getString("state")).isEqualTo("UNKNOWN")
        // The existing Redis response still replays to its original scope. Evicting it exercises
        // the durable fallback, where the legacy row is refused.
        evictCreatorRedisKey()
        assertThat(create().statusCode).isEqualTo(409)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `edge customers sharing one service principal have separate receipts`() {
        val key = UUID.randomUUID().toString()
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val actorA = UUID.randomUUID()
        val actorB = UUID.randomUUID()
        assertThat(create(key, partyA, actorA).statusCode).isEqualTo(201)
        val ownerState = receipt(key = key, party = partyA, actor = actorA).jsonPath().getString("state")
        val otherActorState = receipt(key = key, party = partyA, actor = actorB).jsonPath().getString("state")
        assertThat(ownerState).isEqualTo("FOUND")
        assertThat(otherActorState).isEqualTo("UNKNOWN")
        assertThat(create(key, partyA, actorB).statusCode).isEqualTo(409)
        val otherPartyState = receipt(key = key, party = partyB, actor = actorA).jsonPath().getString("state")
        assertThat(otherPartyState).isEqualTo("UNKNOWN")
        assertThat(create(key, partyB, actorA).statusCode).isEqualTo(409)
        assertThat(create(key, partyA).statusCode).isEqualTo(400)
    }

    private fun create(key: String = idempotencyKey, party: UUID? = null, actor: UUID? = null) = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }
        .apply { if (actor != null) header("X-Customer-Actor-Id", actor.toString()) }
        .body(body)
        .post("/api/v1/sepa-payments")

    private fun receipt(
        key: String = idempotencyKey,
        account: UUID = debtorAccountId,
        party: UUID? = null,
        actor: UUID? = null,
    ) = RestAssured.given()
        .contentType("application/json")
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }
        .apply { if (actor != null) header("X-Customer-Actor-Id", actor.toString()) }
        .body("""{"idempotencyKey":"$key","debtorAccountId":"$account"}""")
        .post("/api/v1/sepa-payments/receipts/lookup")

    private fun rows(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM sepa_payments WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, idempotencyKey)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun evictCreatorRedisKey() {
        val redisKey = "idempotency:" + IdempotencyScope("sepa-payment", "receipt-operator-a").storeKey(idempotencyKey)
        assertThat(redis.key().del(redisKey).await().indefinitely()).isEqualTo(1)
    }

    private companion object {
        val idempotencyKey = UUID.randomUUID().toString()
        val debtorAccountId = UUID.randomUUID()
        var paymentId: String? = null
        val body = """
            {"type":"SCT","debtorAccountId":"$debtorAccountId",
             "debtorIban":"CZ6508000000192000145399","debtorName":"Alice Example",
             "creditorIban":"DE89370400440532013000","creditorName":"Berlin Utility",
             "creditorBic":"COBADEFFXXX","amount":12.34,"currency":"EUR",
             "remittanceInfo":"Receipt test","endToEndId":null}
        """.trimIndent()
    }
}
