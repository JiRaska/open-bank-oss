// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.e2e

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

/**
 * The standing-order customer journey over the real HTTP surface: create → read → pause → resume
 * → cancel → gone. Ordered because the lifecycle is a state machine — each step is only reachable
 * from the previous one — and `@TestSecurity` fixes one identity per method.
 *
 * The e2e layer answers a different question than the integration tests in this module: those
 * prove one transition against the table (xmin equality, outbox atomicity, idempotent replay);
 * this proves the customer-visible route sequence actually serves the whole lifecycle over HTTP —
 * that a party can create an order, find it again in every read shape, park it, reactivate it and
 * cancel it, with the state each step returns. A wiring regression that deregisters a route
 * (#3371) or answers the wrong status for a lifecycle step fails here even when every table-level
 * assertion stays green.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(StandingOrderLifecycleE2E.NoDispatchResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(StandingOrderLifecycleE2E.AdvisoryAuthzProfile::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class StandingOrderLifecycleE2E {

    /**
     * No OPA sidecar answers in a @QuarkusTest and `application.yaml` defaults `authz.enforce` to
     * true, which fails closed with 503 POLICY_DECISION_POINT_UNAVAILABLE on `@Authorize` routes
     * (pause) — same shape as StandingOrderListenerAuthParityIT.Profile.
     */
    class AdvisoryAuthzProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

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

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    @Test
    @Order(1)
    fun `1 - a party creates a standing order and reads it back in every read shape`() {
        val key = "e2e-${UUID.randomUUID()}"
        createdId = given()
            .contentType("application/json")
            .body(createBody(key))
            .post("/api/v1/standing-orders")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", equalTo("ACTIVE"))
            .extract()
            .path<String>("id")
        idempotencyKey = key

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/{id}", createdId).then()
            .statusCode(200)
            .body("id", equalTo(createdId))
            .body("status", equalTo("ACTIVE"))
            .body("creditorIban", equalTo(CREDITOR_IBAN))

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/party/{partyId}", party).then()
            .statusCode(200)
            .body("id", hasItem(createdId))

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders").then()
            .statusCode(200)
            .body("id", hasItem(createdId))
    }

    @Test
    @Order(2)
    fun `2 - pause parks the order and resume reactivates it`() {
        given().contentType("application/json").post("/api/v1/standing-orders/{id}/pause", createdId).then()
            .statusCode(200)
            .body("status", equalTo("PAUSED"))

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/{id}", createdId).then()
            .statusCode(200)
            .body("status", equalTo("PAUSED"))

        given().contentType("application/json").post("/api/v1/standing-orders/{id}/resume", createdId).then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"))
    }

    @Test
    @Order(3)
    fun `3 - cancel ends the lifecycle - the order reads CANCELLED in every read shape`() {
        given().contentType("application/json").delete("/api/v1/standing-orders/{id}", createdId).then()
            .statusCode(org.hamcrest.Matchers.isOneOf(200, 204))

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/{id}", createdId).then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))
            .body("updatedAt", notNullValue())

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/party/{partyId}", party).then()
            .statusCode(200)
            .body("find { it.id == '$createdId' }.status", equalTo("CANCELLED"))
    }

    @Test
    @Order(4)
    fun `4 - a replayed create with the same idempotency key returns the cancelled order and adds nothing`() {
        val replay = given()
            .contentType("application/json")
            .body(createBody(idempotencyKey))
            .post("/api/v1/standing-orders")
            .then()
            .statusCode(201)
            .extract()
            .path<String>("id")

        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(createdId)

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/{id}", createdId).then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))

        given().header("Content-Type", "application/json").get("/api/v1/standing-orders/party/{partyId}", party).then()
            .statusCode(200)
            .body("findAll { it.id == '$createdId' }.size()", equalTo(1))
    }

    private fun createBody(key: String) = """
        {
          "idempotencyKey": "$key",
          "partyId": "$party",
          "debitAccountId": "$account",
          "creditorIban": "$CREDITOR_IBAN",
          "creditorName": "Pronajimatel",
          "creditorBic": null,
          "amountMinorUnits": 150000,
          "currency": "CZK",
          "frequency": "MONTHLY",
          "paymentType": "DOMESTIC",
          "remittanceInfo": null,
          "startDate": "${LocalDate.now().plusDays(1)}",
          "endDate": null
        }
    """.trimIndent()

    private companion object {
        const val CREDITOR_IBAN = "CZ6508000000192000145399"
        lateinit var createdId: String
        lateinit var idempotencyKey: String
    }
}
