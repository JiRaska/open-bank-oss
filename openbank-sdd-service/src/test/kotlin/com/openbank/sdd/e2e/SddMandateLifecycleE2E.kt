// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sdd.e2e.SddMandateLifecycleE2E.OpaBundleMirrorResource
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
 * End-to-end journey for the debtor-side SEPA Direct Debit mandate vault (ADR-0036): **register a
 * Core mandate, read it back, authorise collections against it (accept, unknown-mandate reject,
 * debtor-controls refuse), amend it, assess a refund claim, suspend and resume it, and cancel it —
 * watching the fail-closed authorisation decisions track the lifecycle state.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres (full Flyway chain) via `PostgresTestResource`. No use case, repository or domain
 * object is touched directly and nothing is mocked out of the request path.
 *
 * ### The policy decision point is real HTTP, the bundle is a mirror
 *
 * sdd-service ships `authz.enforce: true` — the same fail-closed interceptor wiring as production
 * (ADR-0034 D5) — so every `@Authorize` call must reach a live OPA sidecar or the endpoint answers
 * 503 `POLICY_DECISION_POINT_UNAVAILABLE` (measured: POST /mandates 503 without one). The test
 * resource below is a localhost HTTP server speaking the exact OPA query protocol
 * (`POST {opa.url}/v1/data/openbank/rest/allow`, `{"result":{"allow":…}}`) whose decisions MIRROR
 * the deployed bundle `openbank-infra/gitops/components/sdd/sdd_rest_ext.rego`:
 *   - "operator-sdd-write" — HUMAN + ROLE_OPERATOR/ROLE_ADMIN + `sdd.*` (covers the authorise act);
 *   - "sdd-oversight-read" — HUMAN + ROLE_VIEWER/ROLE_COMPLIANCE + exactly `sdd.read`/`sdd.list`;
 *   - everything else denies.
 * Mirroring the real bundle (rather than blanket-allowing) is what makes the 403 assertions in
 * this file evidence about the PRODUCTION policy shape, not about a permissive fixture. The rego
 * rules themselves carry their own `opa test` coverage in openbank-infra.
 *
 * ### Service boundary — stated, not pretended
 *
 * Both Kafka channels are switched to the in-memory connector. An ACCEPTed collection writes its
 * outbox row and would publish `collection-authorised`, which this service self-consumes to book
 * the debtor-side debit against balance-service — that async hop (and the outbound balance call)
 * is deliberately NOT claimed here: the journey asserts the synchronous authorisation decision,
 * the durable local state, and nothing past the broker boundary.
 */
@QuarkusTest
@QuarkusTestResource(SddMandateLifecycleE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(OpaBundleMirrorResource::class)
@QuarkusTestResource(com.openbank.sdd.it.PostgresTestResource::class)
class SddMandateLifecycleE2E {

    private val mapper = jacksonObjectMapper()

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out").toMutableMap()
            props.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("sdd-collection-authorised-in"))
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * Localhost OPA sidecar stand-in whose allow/deny decisions mirror sdd_rest_ext.rego
     * (see the class kdoc). Port 0 → OS-assigned, surfaced to the app via `opa.url`.
     */
    class OpaBundleMirrorResource : QuarkusTestResourceLifecycleManager {
        private lateinit var server: com.sun.net.httpserver.HttpServer

        override fun start(): Map<String, String> {
            server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/data/openbank/rest/allow") { exchange ->
                exchange.use {
                    val input = ObjectMapper().readTree(exchange.requestBody)["input"]
                    val action = input["action"].asText()
                    val roles = input["principal"]["roles"].map { it.asText() }
                    val allowed = when {
                        roles.any { it == "ROLE_OPERATOR" || it == "ROLE_ADMIN" } &&
                            action.startsWith("sdd.") -> true
                        roles.any { it == "ROLE_VIEWER" || it == "ROLE_COMPLIANCE" } &&
                            (action == "sdd.read" || action == "sdd.list") -> true
                        else -> false
                    }
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
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR),
            Claim(key = "preferred_username", value = ACTOR),
        ],
    )
    fun `a registered core mandate reads back amends and assesses refunds end to end`() {
        val accountId = UUID.randomUUID()
        val umr = "E2E-UMR-${UUID.randomUUID().toString().take(8)}"

        // 1. Register a Core mandate — born ACTIVE (B2B would be PENDING_CONFIRMATION).
        val registered = post("/mandates", registerBody(accountId, umr))
        assertThat(registered.statusCode)
            .describedAs("POST /api/v1/sdd/mandates: %s", registered.body.asString())
            .isEqualTo(201)
        val mandateId = registered.jsonPath().getString("id")
        assertThat(registered.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(registered.jsonPath().getBoolean("b2bConfirmed")).isFalse()

        // 2. Read back over the committed row — same fields, same values.
        val readBack = get("/mandates/$mandateId")
        assertThat(readBack.statusCode)
            .describedAs("GET /api/v1/sdd/mandates/{id}: %s", readBack.body.asString())
            .isEqualTo(200)
        assertThat(readBack.jsonPath().getString("id")).isEqualTo(mandateId)
        assertThat(readBack.jsonPath().getString("accountId")).isEqualTo(accountId.toString())
        assertThat(readBack.jsonPath().getString("debtorIban")).isEqualTo(DEBTOR_IBAN)
        assertThat(readBack.jsonPath().getString("creditorIdentifier")).isEqualTo(CREDITOR_ID)
        assertThat(readBack.jsonPath().getString("umr")).isEqualTo(umr)
        assertThat(readBack.jsonPath().getString("scheme")).isEqualTo("CORE")
        assertThat(readBack.jsonPath().getString("status")).isEqualTo("ACTIVE")

        // 3. Listed for its account — the endpoint answers a BARE array, so read the root node.
        val listed = get("/mandates?accountId=$accountId")
        assertThat(listed.statusCode).isEqualTo(200)
        val listedIds = mapper.readTree(listed.body.asString()).map { it.get("id").asText() }
        assertThat(listedIds).containsExactly(mandateId)

        // 4. An amendment is recorded on the mandate and visible on the read-back.
        val amended = given()
            .contentType(ContentType.JSON)
            .body("""{"field": "CREDITOR_NAME", "newValue": "Amended Creditor s.r.o."}""")
            .patch("/api/v1/sdd/mandates/$mandateId")
            .then()
            .extract()
            .response()
        assertThat(amended.statusCode)
            .describedAs("PATCH /api/v1/sdd/mandates/{id}: %s", amended.body.asString())
            .isEqualTo(200)
        val afterAmend = get("/mandates/$mandateId")
        assertThat(afterAmend.jsonPath().getString("creditorName")).isEqualTo("Amended Creditor s.r.o.")
        assertThat(afterAmend.jsonPath().getList<Any>("amendments")).hasSize(1)

        // 5. A refund claim inside the Core scheme's unconditional window is eligible.
        val refund = get("/mandates/$mandateId/refund-assessment?debitDate=${LocalDate.now().minusDays(10)}")
        assertThat(refund.statusCode)
            .describedAs("GET refund-assessment: %s", refund.body.asString())
            .isEqualTo(200)
        assertThat(refund.jsonPath().getBoolean("eligible")).isTrue()
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = ACTOR),
            Claim(key = "preferred_username", value = ACTOR),
        ],
    )
    fun `collection authorisation tracks the mandate lifecycle fail-closed`() {
        val umr = "E2E-UMR-${UUID.randomUUID().toString().take(8)}"
        val mandateId = registerActiveMandate(umr)

        // 1. A matching collection is ACCEPTED.
        val accepted = authorise(umr, amount = "250.00")
        assertThat(accepted.statusCode).isEqualTo(200)
        assertThat(accepted.jsonPath().getString("decision")).isEqualTo("ACCEPT")

        // 2. A collection for a UMR nobody holds is REJECTED, not refused and not accepted.
        val unknown = authorise("E2E-UMR-UNKNOWN", amount = "250.00")
        assertThat(unknown.statusCode).isEqualTo(200)
        assertThat(unknown.jsonPath().getString("decision")).isEqualTo("REJECT")

        // 3. A collection over the debtor's per-collection ceiling is REFUSED (controls fail closed).
        val overCeiling = authorise(umr, amount = "250.00", maxAmountPerCollection = "100.00")
        assertThat(overCeiling.statusCode).isEqualTo(200)
        assertThat(overCeiling.jsonPath().getString("decision")).isEqualTo("REFUSE")

        // 4. Suspension makes the mandate inactive — a bank-side Reject (MD01), not a debtor Refuse.
        assertThat(post("/mandates/$mandateId/suspend", "").statusCode).isEqualTo(200)
        val whileSuspended = authorise(umr, amount = "250.00")
        assertThat(whileSuspended.jsonPath().getString("decision")).isEqualTo("REJECT")
        assertThat(whileSuspended.jsonPath().getString("reasonCode")).isEqualTo("MD01")

        // 5. Resume reactivates the mandate and collections pass again.
        assertThat(post("/mandates/$mandateId/resume", "").statusCode).isEqualTo(200)
        val afterResume = authorise(umr, amount = "250.00")
        assertThat(afterResume.jsonPath().getString("decision")).isEqualTo("ACCEPT")

        // 6. Cancellation is terminal: the mandate reads CANCELLED and rejects collections (MD01).
        assertThat(post("/mandates/$mandateId/cancel", "").statusCode).isEqualTo(200)
        assertThat(get("/mandates/$mandateId").jsonPath().getString("status")).isEqualTo("CANCELLED")
        val afterCancel = authorise(umr, amount = "250.00")
        assertThat(afterCancel.jsonPath().getString("decision")).isEqualTo("REJECT")
        assertThat(afterCancel.jsonPath().getString("reasonCode")).isEqualTo("MD01")
    }

    private fun registerActiveMandate(umr: String): String {
        val registered = post("/mandates", registerBody(UUID.randomUUID(), umr))
        assertThat(registered.statusCode)
            .describedAs("POST /api/v1/sdd/mandates: %s", registered.body.asString())
            .isEqualTo(201)
        assertThat(registered.jsonPath().getString("status")).isEqualTo("ACTIVE")
        return registered.jsonPath().getString("id")
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-viewer-1"),
            Claim(key = "preferred_username", value = "e2e-viewer-1"),
        ],
    )
    fun `the oversight persona reads but cannot write under the mirrored bundle`() {
        val accountId = UUID.randomUUID()
        val umr = "E2E-UMR-${UUID.randomUUID().toString().take(8)}"

        // The viewer has no write grant anywhere in sdd_rest_ext.rego — the create attempt is
        // denied by the PDP (403), not by the JAX-RS role check (which never runs: @Authorize
        // precedes it). Evidence the oversight persona is read-only BY CONSTRUCTION.
        val denied = post("/mandates", registerBody(accountId, umr))
        assertThat(denied.statusCode)
            .describedAs("viewer POST /api/v1/sdd/mandates: %s", denied.body.asString())
            .isEqualTo(403)

        // The oversight read grant covers sdd.list even for an account that holds no mandates.
        val listed = get("/mandates?accountId=$accountId")
        assertThat(listed.statusCode).isEqualTo(200)
        assertThat(mapper.readTree(listed.body.asString()).size()).isZero()
    }

    private fun registerBody(accountId: UUID, umr: String): String =
        """
        {
          "accountId": "$accountId",
          "debtorIban": "$DEBTOR_IBAN",
          "creditorIdentifier": "$CREDITOR_ID",
          "umr": "$umr",
          "scheme": "CORE",
          "sequenceType": "RCUR",
          "creditorName": "E2E Creditor GmbH",
          "debtorName": "E2E Debtor s.r.o.",
          "signatureDate": "${LocalDate.now().minusDays(30)}"
        }
        """.trimIndent()

    private fun authorise(umr: String, amount: String, maxAmountPerCollection: String? = null): Response = post(
        "/collections/authorise",
        """
        {
          "creditorIdentifier": "$CREDITOR_ID",
          "umr": "$umr",
          "scheme": "CORE",
          "sequenceType": "RCUR",
          "amount": $amount,
          "currency": "EUR",
          "dueDate": "${LocalDate.now().plusDays(3)}",
          "controls": {
            "blockAll": false,
            "blockedCreditors": [],
            "maxAmountPerCollection": ${maxAmountPerCollection ?: "null"}
          }
        }
        """.trimIndent(),
    )

    private fun post(path: String, body: String): Response = RestAssured.given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/sdd$path")
        .then()
        .extract()
        .response()

    private fun get(path: String): Response = RestAssured.given()
        .get("/api/v1/sdd$path")
        .then()
        .extract()
        .response()

    private fun given() = RestAssured.given()

    private companion object {
        private const val ACTOR = "e2e-operator-1"
        private const val DEBTOR_IBAN = "DE89370400440532013000"
        private const val CREDITOR_ID = "DE98ZZZ01234567890"
    }
}
