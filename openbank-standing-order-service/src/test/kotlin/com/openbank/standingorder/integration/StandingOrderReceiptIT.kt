// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.jwt.Claim
import io.quarkus.test.security.jwt.JwtSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/** Real HTTP + Postgres proof of the durable customer receipt and fail-closed legacy path. */
@QuarkusTest
@QuarkusTestResource(StandingOrderReceiptIT.NoDispatchResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class StandingOrderReceiptIT {
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

    @Inject lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    @JwtSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER),
            Claim(key = "sub", value = EDGE),
            Claim(key = "preferred_username", value = EDGE),
            Claim(key = "azp", value = "openbank-edge"),
        ],
    )
    fun `lost response resolves only for original actor party and account`() {
        val key = "receipt-${UUID.randomUUID()}"
        val party = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        val created = create(key, party, actor, account)
        assertThat(created.statusCode).describedAs(created.body.asString()).isEqualTo(201)
        val id = created.jsonPath().getString("id")

        val receipt = lookup(key, party, actor, account)
        assertThat(receipt.statusCode).isEqualTo(200)
        assertThat(receipt.jsonPath().getString("state")).isEqualTo("FOUND")
        assertThat(receipt.jsonPath().getString("orderId")).isEqualTo(id)
        assertThat(receipt.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(receipt.body.asString()).doesNotContain(key)
        dataSource.connection.use { connection ->
            connection.prepareStatement("update standing_orders set status = 'PAUSED' where id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(id))
                assertThat(ps.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(lookup(key, party, actor, account).jsonPath().getString("status")).isEqualTo("PAUSED")
        val detail = RestAssured.given().get("/api/v1/standing-orders/$id")
        assertThat(detail.statusCode).isEqualTo(200)
        assertThat(detail.body.asString()).doesNotContain(
            "requestHash",
            "initiatingPrincipal",
            "initiatingActorId",
            "initiatingMandateId",
        )
        assertThat(lookup(key, party, actor, UUID.randomUUID()).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(lookup(key, party, UUID.randomUUID(), account).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(lookup(key, UUID.randomUUID(), actor, account).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(lookup("absent-${UUID.randomUUID()}", party, actor, account).jsonPath().getString("state"))
            .isEqualTo("UNKNOWN")
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    @JwtSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = EDGE),
            Claim(key = "preferred_username", value = EDGE), Claim(key = "azp", value = "openbank-edge"),
        ],
    )
    fun `replay compares whole payload and actor while legacy row cannot yield a receipt`() {
        val key = "replay-${UUID.randomUUID()}"
        val party = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        val first = create(key, party, actor, account)
        assertThat(first.statusCode).describedAs(first.body.asString()).isEqualTo(201)
        assertThat(create(key, party, actor, account).jsonPath().getString("id"))
            .isEqualTo(first.jsonPath().getString("id"))
        assertThat(create(key, party, actor, account, amount = 2600).statusCode).isEqualTo(409)
        assertThat(create(key, party, UUID.randomUUID(), account).statusCode).isEqualTo(409)
        assertThat(create(key, party, actor, UUID.randomUUID()).statusCode).isEqualTo(409)

        dataSource.connection.use { connection ->
            connection.prepareStatement("update standing_orders set request_hash = null where id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(first.jsonPath().getString("id")))
                assertThat(ps.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(lookup(key, party, actor, account).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(create(key, party, actor, account).statusCode).isEqualTo(409)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    @JwtSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = EDGE),
            Claim(key = "preferred_username", value = EDGE), Claim(key = "azp", value = "openbank-edge"),
        ],
    )
    fun `older edge can create during rollout but cannot expose an unbound receipt`() {
        val key = "old-edge-${UUID.randomUUID()}"
        val party = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        assertThat(create(key, party, null, account).statusCode).isEqualTo(201)
        assertThat(lookup(key, party, actor, account).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(create(key, party, actor, account).statusCode).isEqualTo(409)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    @JwtSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = EDGE),
            Claim(key = "preferred_username", value = EDGE), Claim(key = "azp", value = "openbank-edge"),
        ],
    )
    fun `regrant for same actor and company cannot recover former mandate receipt`() {
        val key = "mandate-${UUID.randomUUID()}"
        val company = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        val original = UUID.randomUUID()
        val regrant = UUID.randomUUID()
        val created = create(key, company, actor, account, mandateId = original)
        assertThat(created.statusCode).describedAs(created.body.asString()).isEqualTo(201)
        assertThat(lookup(key, company, actor, account, original).jsonPath().getString("state"))
            .isEqualTo("FOUND")
        // A second simultaneous role must not lock the customer out of the original receipt.
        val bothActive = setOf(original, regrant)
        val withBoth = lookup(key, company, actor, account, activeMandateIds = bothActive)
        assertThat(withBoth.jsonPath().getString("state")).isEqualTo("FOUND")
        val replayed = create(key, company, actor, account, mandateId = regrant, activeMandateIds = bothActive)
        assertThat(replayed.jsonPath().getString("id")).isEqualTo(created.jsonPath().getString("id"))
        assertThat(lookup(key, company, actor, account, regrant).jsonPath().getString("state"))
            .isEqualTo("UNKNOWN")
        assertThat(lookup(key, company, actor, account).jsonPath().getString("state"))
            .isEqualTo("UNKNOWN")
        assertThat(create(key, company, actor, account, mandateId = regrant).statusCode).isEqualTo(409)
        dataSource.connection.use { connection ->
            connection.prepareStatement("select initiating_mandate_id from standing_orders where idempotency_key = ?")
                .use { ps ->
                    ps.setString(1, key)
                    ps.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getObject(1, UUID::class.java)).isEqualTo(original)
                    }
                }
        }
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_API"])
    @JwtSecurity(
        claims = [
            Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = EDGE),
            Claim(key = "preferred_username", value = EDGE), Claim(key = "azp", value = "openbank-edge"),
        ],
    )
    fun `racing changed requests leave one durable instruction and a conflict`() {
        val key = "race-${UUID.randomUUID()}"
        val party = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = listOf(2500L, 2600L).map { amount ->
                CompletableFuture.supplyAsync({
                    start.await()
                    create(key, party, actor, account, amount)
                }, executor)
            }
            start.countDown()
            assertThat(calls.map { it.join().statusCode }).containsExactlyInAnyOrder(201, 409)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "select count(*) from standing_orders where idempotency_key = ?",
                ).use { ps ->
                    ps.setString(1, key)
                    ps.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getInt(1)).isEqualTo(1)
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    @JwtSecurity(claims = [Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = "operator-1")])
    fun `non-edge principal cannot forge customer provenance`() {
        val party = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val account = UUID.randomUUID()
        val key = "forged-${UUID.randomUUID()}"
        assertThat(create(key, party, actor, account).statusCode).isEqualTo(403)
        assertThat(lookup(key, party, actor, account).statusCode).isEqualTo(403)
    }

    private fun create(
        key: String,
        party: UUID,
        actor: UUID?,
        account: UUID,
        amount: Long = 2500,
        mandateId: UUID? = null,
        activeMandateIds: Set<UUID> = mandateId?.let { setOf(it) } ?: emptySet(),
    ): Response {
        val request = RestAssured.given()
            .contentType("application/json")
            .header("X-Customer-Party-Id", party.toString())
        if (actor != null) request.header("X-Customer-Actor-Id", actor.toString())
        if (mandateId != null) request.header("X-Customer-Mandate-Id", mandateId.toString())
        if (activeMandateIds.isNotEmpty()) {
            request.header("X-Customer-Mandate-Ids", activeMandateIds.joinToString(","))
        }
        return request.body(
            """{
            "idempotencyKey":"$key", "partyId":"$party", "debitAccountId":"$account",
            "creditorIban":"CZ6508000000192000145399", "creditorName":"Payee", "creditorBic":null,
            "amountMinorUnits":$amount, "currency":"CZK", "frequency":"MONTHLY", "paymentType":"DOMESTIC",
            "remittanceInfo":null, "startDate":"${LocalDate.now().plusDays(1)}", "endDate":null
        }""",
        )
            .post("/api/v1/standing-orders")
    }

    private fun lookup(
        key: String,
        party: UUID,
        actor: UUID,
        account: UUID,
        mandateId: UUID? = null,
        activeMandateIds: Set<UUID> = mandateId?.let { setOf(it) } ?: emptySet(),
    ): Response {
        val request = RestAssured.given()
            .contentType("application/json")
            .header("X-Customer-Party-Id", party.toString())
            .header("X-Customer-Actor-Id", actor.toString())
        if (activeMandateIds.isNotEmpty()) {
            request.header("X-Customer-Mandate-Ids", activeMandateIds.joinToString(","))
        }
        return request.body("""{"idempotencyKey":"$key","debitAccountId":"$account"}""")
            .post("/api/v1/standing-orders/receipts/lookup")
    }

    private companion object {
        const val EDGE = "service-account-openbank-edge"
        const val ISSUER = "https://issuer.example"
    }
}
