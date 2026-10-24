// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journeys for the standing-order vault: **create → read-back → list → pause/resume →
 * cancel** for a recurring order, **create → confirmed execution → COMPLETED** for a ONCE order,
 * and the **policy gate on pause** — the service's only `@Authorize`-annotated endpoint.
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres + Valkey (full Flyway chain) via `PostgresRedisTestResource`. No use case,
 * repository or domain object is touched directly and nothing is mocked out of the request path.
 *
 * ### The policy decision point is real HTTP, the bundle is a mirror
 *
 * standing-order-service ships `authz.enforce: true`, and `pause()` is the one endpoint with
 * `@Authorize(action = "standingOrder.pause")` — without a live OPA sidecar it answers 503
 * `POLICY_DECISION_POINT_UNAVAILABLE`, while create/list/get/resume/cancel/record-* carry only
 * `@RolesAllowed` and never consult the PDP (measured shape, see
 * `openbank-infra/gitops/components/payments/standing_order_rest_ext.rego`). The test resource
 * below is a localhost HTTP server speaking the exact OPA query protocol
 * (`POST {opa.url}/v1/data/openbank/rest/allow`, `{"result":{"allow":…}}`) whose decisions MIRROR
 * the deployed bundle: HUMAN ROLE_OPERATOR/ROLE_ADMIN on `standingOrder.pause` → allow (the
 * GHSA-58jq remediation keeps the grant human-only); the `service-account-openbank-edge` identity →
 * allow (its one legitimate M2M caller); every other principal → deny. Mirroring the real bundle
 * is what makes the 403 assertions in this file evidence about the PRODUCTION policy shape, not a
 * permissive fixture.
 *
 * ### Service boundary — stated, not pretended
 *
 * Both Kafka channels are switched to the in-memory connector. A due order writes its outbox row
 * and would publish `standing-order-due` downstream (the consumer books the payment and reports
 * back via `standing-order-due-in`) — that async hop is deliberately NOT claimed here: the
 * journeys assert the synchronous state machine over the durable local row, and nothing past the
 * broker boundary.
 */
@QuarkusTest
@QuarkusTestResource(StandingOrderLifecycleE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(StandingOrderLifecycleE2E.OpaBundleMirrorResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class StandingOrderLifecycleE2E {

    private val mapper = jacksonObjectMapper()

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("standing-order-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("standing-order-due-in")

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * Localhost OPA sidecar stand-in whose allow/deny decisions mirror
     * standing_order_rest_ext.rego (see the class kdoc). Port 0 → OS-assigned, surfaced to the
     * app via `opa.url`.
     */
    class OpaBundleMirrorResource : QuarkusTestResourceLifecycleManager {
        private lateinit var server: com.sun.net.httpserver.HttpServer

        override fun start(): Map<String, String> {
            server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/data/openbank/rest/allow") { exchange ->
                exchange.use {
                    val input = ObjectMapper().readTree(exchange.requestBody)["input"]
                    val action = input["action"].asText()
                    val principal = input["principal"]
                    val id = principal["id"].asText("")
                    val roles = principal["roles"].map { it.asText() }
                    // Current-ABI PEP fields (AuthorizeInterceptor): service_account is true for
                    // every token not positively verified as a person, absent only on legacy PEPs.
                    val serviceAccount = principal["service_account"]?.takeIf { it.isBoolean }?.asBoolean()
                    val clientId = principal["client_id"]?.takeIf { it.isTextual }?.asText()

                    // rest.rego: principal_is_machine = NOT(service_account == false); the
                    // operator grant is human-only (GHSA-58jq remediation).
                    val isMachine = serviceAccount != false
                    val humanStaff = serviceAccount == false &&
                        roles.any { it == "ROLE_OPERATOR" || it == "ROLE_ADMIN" }
                    // machine_named: name pinned to the verified client id when the marker is present.
                    val namedEdge = id == "service-account-openbank-edge" &&
                        serviceAccount == true &&
                        clientId == "openbank-edge"

                    val allowed = action == "standingOrder.pause" &&
                        ((humanStaff && !isMachine) || namedEdge)
                    val response = "{\"result\":{\"allow\":$allowed}}".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.write(response)
                }
            }
            server.start()
            return mapOf("opa.url" to "http://127.0.0.1:${server.address.port}")
        }

        override fun stop() = server.stop(0)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `a recurring order reads back pauses resumes and cancels end to end`() {
        val partyId = UUID.randomUUID()
        val key = "E2E-${UUID.randomUUID()}"

        // 1. Create a MONTHLY SEPA_CREDIT order — born ACTIVE with nextExecutionDate == startDate.
        val created = post("", createBody(key, partyId, "MONTHLY", null))
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/standing-orders: %s", created.body.asString())
            .isEqualTo(201)
        val orderId = created.jsonPath().getString("id")
        assertThat(created.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(created.jsonPath().getLong("amountMinorUnits")).isEqualTo(12_500L)
        assertThat(created.jsonPath().getDouble("amount")).isEqualTo(125.0)
        assertThat(created.jsonPath().getString("nextExecutionDate")).isEqualTo(START_DATE.toString())
        assertThat(created.jsonPath().getInt("executionCount")).isZero()

        // 2. Idempotent replay under the same key returns the SAME order — never a second row.
        val replayed = post("", createBody(key, partyId, "MONTHLY", null))
        assertThat(replayed.statusCode).isEqualTo(201)
        assertThat(replayed.jsonPath().getString("id")).isEqualTo(orderId)

        // 3. Read back over the committed row — same fields, same values.
        val readBack = get("/$orderId")
        assertThat(readBack.statusCode)
            .describedAs("GET /api/v1/standing-orders/{id}: %s", readBack.body.asString())
            .isEqualTo(200)
        assertThat(readBack.jsonPath().getString("id")).isEqualTo(orderId)
        assertThat(readBack.jsonPath().getString("partyId")).isEqualTo(partyId.toString())
        assertThat(readBack.jsonPath().getString("debtorAccountId"))
            .describedAs("read-back body: %s", readBack.body.asString())
            .isEqualTo(DEBIT_ACCOUNT_ID)
        assertThat(readBack.jsonPath().getString("creditorIban")).isEqualTo(CREDITOR_IBAN)
        assertThat(readBack.jsonPath().getString("creditorName")).isEqualTo("E2E Creditor GmbH")
        assertThat(readBack.jsonPath().getString("frequency")).isEqualTo("MONTHLY")
        assertThat(readBack.jsonPath().getString("paymentType")).isEqualTo("SEPA_CREDIT")
        assertThat(readBack.jsonPath().getString("currency")).isEqualTo("EUR")

        // 4. Listed by party — exactly ONE row despite the idempotent replay, and it is ours.
        val listed = get("/party/$partyId")
        assertThat(listed.statusCode).isEqualTo(200)
        val listedIds = mapper.readTree(listed.body.asString()).map { it.get("id").asText() }
        assertThat(listedIds).containsExactly(orderId)

        // 5. Pause is policy-gated and lands PAUSED; resume lands ACTIVE again.
        val paused = post("/$orderId/pause", "")
        assertThat(paused.statusCode)
            .describedAs("POST /api/v1/standing-orders/{id}/pause: %s", paused.body.asString())
            .isEqualTo(200)
        assertThat(paused.jsonPath().getString("status")).isEqualTo("PAUSED")
        assertThat(get("/$orderId").jsonPath().getString("status")).isEqualTo("PAUSED")
        val resumed = post("/$orderId/resume", "")
        assertThat(resumed.statusCode).isEqualTo(200)
        assertThat(resumed.jsonPath().getString("status")).isEqualTo("ACTIVE")

        // 6. Cancellation is 204 and terminal: the read-back reports CANCELLED.
        val cancelled = RestAssured.given().delete("/api/v1/standing-orders/$orderId")
            .then().extract().response()
        assertThat(cancelled.statusCode).isEqualTo(204)
        assertThat(get("/$orderId").jsonPath().getString("status")).isEqualTo("CANCELLED")

        // 7. Pausing a CANCELLED order is a domain require() → 400 (cancel is terminal).
        val pauseAfterCancel = post("/$orderId/pause", "")
        assertThat(pauseAfterCancel.statusCode).isEqualTo(400)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `a once order completes only when its execution is confirmed`() {
        // 1. Create a ONCE order — ACTIVE until its single execution is CONFIRMED (#12839: it
        //    used to complete on scheduling, before any money moved).
        val created = post("", createBody("E2E-${UUID.randomUUID()}", UUID.randomUUID(), "ONCE", null))
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/standing-orders: %s", created.body.asString())
            .isEqualTo(201)
        val orderId = created.jsonPath().getString("id")
        assertThat(created.jsonPath().getString("status")).isEqualTo("ACTIVE")

        // 2. The execution confirmation is what completes a ONCE order.
        val confirmed = given()
            .patch("/api/v1/standing-orders/$orderId/record-execution")
            .then().extract().response()
        assertThat(confirmed.statusCode)
            .describedAs("PATCH record-execution: %s", confirmed.body.asString())
            .isEqualTo(200)
        assertThat(confirmed.jsonPath().getString("status")).isEqualTo("COMPLETED")
        assertThat(get("/$orderId").jsonPath().getString("status")).isEqualTo("COMPLETED")

        // 3. A COMPLETED order refuses the lifecycle transitions that require ACTIVE (400 via
        //    the domain require), so it can never move money again.
        assertThat(post("/$orderId/resume", "").statusCode).isEqualTo(400)
        assertThat(given().patch("/api/v1/standing-orders/$orderId/record-failure").statusCode).isEqualTo(400)
        val lateCancel = RestAssured.given().delete("/api/v1/standing-orders/$orderId")
            .then().extract().response()
        assertThat(lateCancel.statusCode).isEqualTo(400)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `consecutive failures escalate the order to failed`() {
        val created = post("", createBody("E2E-${UUID.randomUUID()}", UUID.randomUUID(), "MONTHLY", null))
        assertThat(created.statusCode).isEqualTo(201)
        val orderId = created.jsonPath().getString("id")

        // Failure 1 and 2 keep the order ACTIVE (it is re-picked by the next sweep).
        assertThat(given().patch("/api/v1/standing-orders/$orderId/record-failure").statusCode).isEqualTo(200)
        assertThat(given().patch("/api/v1/standing-orders/$orderId/record-failure").statusCode).isEqualTo(200)
        assertThat(get("/$orderId").jsonPath().getString("status")).isEqualTo("ACTIVE")

        // Failure 3 crosses MAX_CONSECUTIVE_FAILURES and the order is FAILED.
        val third = given().patch("/api/v1/standing-orders/$orderId/record-failure").then().extract().response()
        assertThat(third.statusCode).isEqualTo(200)
        assertThat(third.jsonPath().getString("status")).isEqualTo("FAILED")
        assertThat(get("/$orderId").jsonPath().getString("status")).isEqualTo("FAILED")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API", "ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "sub", value = "service-account-openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `the shared backend service account cannot pause a standing order`() {
        // GHSA-58jq-9hq3-66jr (#4228): the shared `openbank-services` identity held
        // ROLE_OPERATOR in the docker/CI realms, and an older role-only rule admitted it to
        // pause — an every-service mandate-schedule-write primitive. The deployed bundle now
        // pins the grant to humans and to the one edge client; the mirror enforces the same.
        val created = post("", createBody("E2E-${UUID.randomUUID()}", UUID.randomUUID(), "MONTHLY", null))
        assertThat(created.statusCode).isEqualTo(201)
        val orderId = created.jsonPath().getString("id")

        // The JAX-RS role check PASSES (ROLE_OPERATOR is @RolesAllowed) — the 403 comes from
        // the policy decision point, which is the layer the remediation lives in.
        val denied = post("/$orderId/pause", "")
        assertThat(denied.statusCode)
            .describedAs("shared service account POST pause: %s", denied.body.asString())
            .isEqualTo(403)
        assertThat(get("/$orderId").jsonPath().getString("status")).isEqualTo("ACTIVE")
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = OPERATOR),
            Claim(key = "preferred_username", value = OPERATOR),
        ],
    )
    fun `an edit replaces an active order in one step`() {
        // #10281: replacesStandingOrderId creates the replacement and cancels the old order in
        // ONE transaction — never a moment where both run (double debit) or neither does.
        val partyId = UUID.randomUUID()
        val original = post("", createBody("E2E-${UUID.randomUUID()}", partyId, "MONTHLY", null))
        assertThat(original.statusCode).isEqualTo(201)
        val originalId = original.jsonPath().getString("id")

        val replacement = post("", createBody("E2E-${UUID.randomUUID()}", partyId, "MONTHLY", originalId))
        assertThat(replacement.statusCode)
            .describedAs("POST create-with-replacement: %s", replacement.body.asString())
            .isEqualTo(201)
        val replacementId = replacement.jsonPath().getString("id")
        assertThat(replacementId).isNotEqualTo(originalId)

        // The party listing shows exactly the two orders; the old one is CANCELLED, the new
        // one ACTIVE — the double-debit window never existed.
        val listed = mapper.readTree(get("/party/$partyId").body.asString())
        assertThat(listed.size()).isEqualTo(2)
        val byId = listed.associateBy { it.get("id").asText() }
        assertThat(byId.getValue(originalId).get("status").asText()).isEqualTo("CANCELLED")
        assertThat(byId.getValue(replacementId).get("status").asText()).isEqualTo("ACTIVE")
    }

    private fun createBody(key: String, partyId: UUID, frequency: String, replaces: String?): String =
        """
        {
          "idempotencyKey": "$key",
          "partyId": "$partyId",
          "debitAccountId": "$DEBIT_ACCOUNT_ID",
          "debtorIban": "$DEBTOR_IBAN",
          "debtorName": "E2E Debtor s.r.o.",
          "creditorIban": "$CREDITOR_IBAN",
          "creditorName": "E2E Creditor GmbH",
          "creditorBic": "DEUTDEFF",
          "amountMinorUnits": 12500,
          "currency": "EUR",
          "frequency": "$frequency",
          "paymentType": "SEPA_CREDIT",
          "remittanceInfo": "E2E standing order",
          "startDate": "$START_DATE"${replaces?.let { ",\n          \"replacesStandingOrderId\": \"$it\"" } ?: ""}
        }
        """.trimIndent()

    private fun post(path: String, body: String): Response = RestAssured.given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/standing-orders$path")
        .then()
        .extract()
        .response()

    private fun get(path: String): Response = RestAssured.given()
        .get("/api/v1/standing-orders$path")
        .then()
        .extract()
        .response()

    private fun given() = RestAssured.given()

    private companion object {
        private const val OPERATOR = "e2e-operator-1"
        private const val DEBTOR_IBAN = "DE89370400440532013000"
        private const val CREDITOR_IBAN = "DE75512108001245126199"
        private const val DEBIT_ACCOUNT_ID = "a2a2a2a2-a2a2-42a2-82a2-a2a2a2a2a2a2"
        private val START_DATE: LocalDate = LocalDate.now().plusDays(1)
    }
}
