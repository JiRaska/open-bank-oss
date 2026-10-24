// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journeys for the SCT Inst rail: **submit → settle → recall**, the idempotent replay
 * that never double-settles, validation that persists nothing, and the fail-closed recall gate.
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres (full Flyway chain) via `PostgresRedpandaRedisTestResource`. No use case,
 * repository or domain object is touched directly. The sanctions screen, the scheme gateway and
 * the settlement booking are the module's `@Alternative` stubs (`StubScreeningBeans`) — CLEAR,
 * ACSC, funds booked — and the profile below turns scheme submission ON so the submit path runs
 * its full ADR-0104/0108 chain: screening → pacs.008 → ACSC → settlement → SETTLED. `authz.enforce`
 * defaults to false on this service (advisory), so no OPA sidecar is consulted.
 *
 * ### Service boundary — stated, not pretended
 *
 * The stubbed trio above is exactly what this E2E claims: the synchronous state machine over the
 * durable local row. What leaves the process — the outbox rows dispatched to Redpanda — is not
 * asserted here; the outbox-dispatch conformance ITs own that boundary.
 */
@QuarkusTest
@TestProfile(SctInstLifecycleE2E.SchemeEnabledProfile::class)
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
class SctInstLifecycleE2E {

    private val mapper = jacksonObjectMapper()

    class SchemeEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.sct-inst.scheme-submission.enabled" to "true",
            // Same as SctInstSettlementAtomicityIT: dispatch off keeps the outbox rows local to
            // the scenario; this E2E asserts the synchronous state machine, not the broker.
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a submitted payment settles and can be recalled end to end`() {
        val debtorAccountId = UUID.randomUUID()
        val key = "E2E-${UUID.randomUUID()}"
        val endToEndId = "E2E${System.currentTimeMillis()}"

        // 1. Submit — screened CLEAR, scheme accepts (ACSC), funds booked: born SETTLED.
        val submitted = post(body = submitBody(debtorAccountId, key, endToEndId, amount = "99.99", currency = "EUR"))
        assertThat(submitted.statusCode)
            .describedAs("POST /api/v1/sepa-instant: %s", submitted.body.asString())
            .isEqualTo(201)
        val paymentId = submitted.jsonPath().getString("paymentId")
        assertThat(submitted.jsonPath().getString("status")).isEqualTo("SETTLED")
        assertThat(submitted.jsonPath().getString("settledAt")).isNotNull()
        assertThat(submitted.jsonPath().getString("executionTimeoutAt")).isNotNull()

        // 2. Read back over the committed row — same fields, same values.
        val readBack = get("/$paymentId")
        assertThat(readBack.statusCode)
            .describedAs("GET /api/v1/sepa-instant/{id}: %s", readBack.body.asString())
            .isEqualTo(200)
        assertThat(readBack.jsonPath().getString("paymentId")).isEqualTo(paymentId)
        assertThat(readBack.jsonPath().getString("status")).isEqualTo("SETTLED")
        assertThat(readBack.jsonPath().getString("debtorIban")).isEqualTo(DEBTOR_IBAN)
        assertThat(readBack.jsonPath().getString("creditorIban")).isEqualTo(CREDITOR_IBAN)
        assertThat(readBack.jsonPath().getDouble("amount")).isEqualTo(99.99)
        assertThat(readBack.jsonPath().getString("currency")).isEqualTo("EUR")
        assertThat(readBack.jsonPath().getString("endToEndId")).isEqualTo(endToEndId)

        // 3. Listed for its debtor account.
        val listed = get("/debtor/$debtorAccountId")
        assertThat(listed.statusCode).isEqualTo(200)
        val listedIds = mapper.readTree(listed.body.asString()).map { it.get("paymentId").asText() }
        assertThat(listedIds).contains(paymentId)

        // 4. Recall — the rail's reversal — lands RECALLED with the reason recorded.
        val recalled = post("/$paymentId/recall", """{"reason": "customer dispute"}""")
        assertThat(recalled.statusCode)
            .describedAs("POST /api/v1/sepa-instant/{id}/recall: %s", recalled.body.asString())
            .isEqualTo(200)
        assertThat(recalled.jsonPath().getString("status")).isEqualTo("RECALLED")
        assertThat(get("/$paymentId").jsonPath().getString("status")).isEqualTo("RECALLED")
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an idempotent replay never double-settles`() {
        val debtorAccountId = UUID.randomUUID()
        val key = "E2E-${UUID.randomUUID()}"

        val first = post(
            body = submitBody(debtorAccountId, key, "E2E-FIRST-${System.currentTimeMillis()}", "10.00", "EUR"),
        )
        assertThat(first.statusCode).isEqualTo(201)
        val paymentId = first.jsonPath().getString("paymentId")

        // Replay under the SAME key — the stored row is returned, nothing new is settled.
        val replay = post(
            body = submitBody(debtorAccountId, key, "E2E-REPLAY-${System.currentTimeMillis()}", "10.00", "EUR"),
        )
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.jsonPath().getString("paymentId")).isEqualTo(paymentId)

        // The debtor listing proves the replay created no second row.
        val listed = mapper.readTree(get("/debtor/$debtorAccountId").body.asString())
        assertThat(listed.map { it.get("paymentId").asText() }).containsExactly(paymentId)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `invalid submissions persist nothing`() {
        val debtorAccountId = UUID.randomUUID()

        // A JSON null body is a 400 (#3038), not a 500.
        val nullBody = RestAssured.given().contentType(ContentType.JSON).body("null")
            .post("/api/v1/sepa-instant").then().extract().response()
        assertThat(nullBody.statusCode).isEqualTo(400)

        // SCT Inst is euro-only: a valid ISO currency other than EUR is refused BEFORE any row,
        // event, screening or downstream call (#11913).
        val wrongCurrency = post(
            body = submitBody(debtorAccountId, "E2E-${UUID.randomUUID()}", "E2E-CZK", "99.99", "CZK"),
        )
        assertThat(wrongCurrency.statusCode)
            .describedAs("non-EUR submit: %s", wrongCurrency.body.asString())
            .isEqualTo(400)
        assertThat(wrongCurrency.body.asString()).contains("CURRENCY_NOT_ALLOWED")

        // An amount the currency's minor unit cannot hold is refused before any row, event,
        // screening or downstream call (#11604).
        val tooPrecise = post(
            body = submitBody(debtorAccountId, "E2E-${UUID.randomUUID()}", "E2E-SCALE", "99.999", "EUR"),
        )
        assertThat(tooPrecise.statusCode)
            .describedAs("over-scale submit: %s", tooPrecise.body.asString())
            .isEqualTo(400)
        assertThat(tooPrecise.body.asString()).contains("AMOUNT_SCALE_EXCEEDED")

        // None of the rejected submissions left a row behind.
        val listed = get("/debtor/$debtorAccountId")
        assertThat(listed.statusCode).isEqualTo(200)
        assertThat(mapper.readTree(listed.body.asString()).size()).isZero()

        // MEASURED, not asserted into the spec: a zero-amount submission is ACCEPTED today
        // (201) — Money.parseInbound defaults requirePositive=false and the resource does not
        // override it, same as sepa-payment. Recorded here as evidence for the maintainers to
        // decide; the E2E pins today's behaviour so a deliberate change either way reddens it.
        val zero = post(body = submitBody(debtorAccountId, "E2E-${UUID.randomUUID()}", "E2E-ZERO", "0", "EUR"))
        assertThat(zero.statusCode).isEqualTo(201)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `recall is fail-closed`() {
        // Unknown payment — 404, not a crash.
        val unknown = post("/${UUID.randomUUID()}/recall", """{"reason": "x"}""")
        assertThat(unknown.statusCode).isEqualTo(404)

        // A payment that is not SETTLED cannot be recalled — the rail's reversal is fail-closed.
        val settled = post(
            body = submitBody(
                UUID.randomUUID(),
                "E2E-${UUID.randomUUID()}",
                "E2E-RC-${System.currentTimeMillis()}",
                "5.00",
                "EUR",
            ),
        )
        assertThat(settled.statusCode).isEqualTo(201)
        val paymentId = settled.jsonPath().getString("paymentId")
        assertThat(post("/$paymentId/recall", """{"reason": "first"}""").statusCode).isEqualTo(200)
        val secondRecall = post("/$paymentId/recall", """{"reason": "second"}""")
        assertThat(secondRecall.statusCode)
            .describedAs("second recall: %s", secondRecall.body.asString())
            .isEqualTo(400)
        assertThat(get("/$paymentId").jsonPath().getString("status")).isEqualTo("RECALLED")
    }

    private fun submitBody(
        debtorAccountId: UUID,
        key: String,
        endToEndId: String,
        amount: String,
        currency: String,
    ): String =
        """
        {
          "idempotencyKey": "$key",
          "debtorAccountId": "$debtorAccountId",
          "debtorIban": "$DEBTOR_IBAN",
          "debtorName": "E2E Debtor s.r.o.",
          "creditorIban": "$CREDITOR_IBAN",
          "creditorName": "E2E Creditor GmbH",
          "creditorBic": "COBADEFFXXX",
          "amount": $amount,
          "currency": "$currency",
          "remittanceInfo": "E2E SCT Inst",
          "endToEndId": "$endToEndId"
        }
        """.trimIndent()

    private fun post(path: String = "", body: String): Response = RestAssured.given()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/v1/sepa-instant$path")
        .then()
        .extract()
        .response()

    private fun get(path: String): Response = RestAssured.given()
        .get("/api/v1/sepa-instant$path")
        .then()
        .extract()
        .response()

    private companion object {
        private const val OPERATOR = "e2e-operator-1"
        private const val DEBTOR_IBAN = "CZ6508000000192000145399"
        private const val CREDITOR_IBAN = "DE89370400440532013000"
    }
}
