// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.settlement.infrastructure.client.BalanceResponse
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal

/**
 * Consumer-driven contract for the two money movements settlement-service issues to
 * balance-service: `POST /api/v1/balances/{accountId}/debit` (the payer leg) and
 * `.../credit` (the payee leg), as sent by
 * [com.openbank.settlement.infrastructure.adapter.BalanceDebitAdapter] and
 * [com.openbank.settlement.infrastructure.adapter.BalanceCreditAdapter].
 *
 * Issue #8345 — the coverage axis. `check-pact-provider-replay.py` guarantees every *committed*
 * pact is replayed before merge; it can say nothing about a cross-service call that never had one,
 * and this was the most money-path of the 27 such calls in the fleet: settlement-service moving
 * customer money between two accounts.
 *
 * ## What was actually broken, and why nothing could see it
 *
 * `BalanceResponse` declared non-nullable `availableBalance` and `currentBalance`. balance-service
 * has never emitted either name — it serializes its `Balance` aggregate, whose money fields are
 * `availableAmount` and `bookedAmount` (see the committed
 * `openbank-account-service-openbank-balance-service` pact, replayed against the running provider).
 * Jackson-Kotlin treats an absent non-nullable constructor parameter as fatal, so every real
 * `credit`/`debit` response would have failed to deserialize — *after* balance-service had already
 * applied the movement, which is the worst place for a settlement leg to fail.
 *
 * Three layers agreed with the defect, which is the part worth remembering:
 *  - both adapter unit tests construct a `mockk` client and hand it a `BalanceResponse` they build
 *    themselves, so the wire shape is never involved;
 *  - `BalanceServiceWireMockResource` — the stub written precisely so that "an actual HTTP request
 *    leaves the process" (#6037) — returned the same invented shape, so the one test designed to
 *    be un-mockable was green about it too;
 *  - a hand-written stub is only ever as right as whoever typed it, and nothing compared it to the
 *    provider.
 *
 * A contract replayed by `BalancePactFolderProviderVerificationTest` is the thing that compares
 * them, and it is why the fix and this pact land together.
 *
 * ## The literal path
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290). Deriving both sides is vacuous: the
 * Pact mock server answers whatever it is asked for, so expectation and request move together and
 * the test stays green against a route that does not exist — how finrep-service shipped a call to a
 * ledger path that never existed (#2269). [assertClientPathsMatchContract] pins the client's
 * annotations to the literals; the provider replay independently answers whether balance-service
 * serves them.
 *
 * The request bodies are SERIALIZED from the production [MoneyMovementRequest] rather than hand
 * typed, so a field added to or renamed on the DTO changes this contract instead of silently
 * drifting from it. `description` is included because the adapters really send it, and
 * balance-service's `BalanceOperationRequest` really ignores it — a fact worth pinning rather than
 * assuming.
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-settlement-service:test
 * --tests "*SettlementBalanceMovementPactConsumerTest*"`) and commit the updated pact JSON in the
 * same PR; `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class SettlementBalanceMovementPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun debitPayerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the settlement payer account")
        .uponReceiving("POST the payer leg of a settlement as a CZK debit")
        .path(EXPECTED_DEBIT_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(DEBIT_BODY)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(balanceBody())
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun creditPayeePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the settlement payee account")
        .uponReceiving("POST the payee leg of a settlement as a CZK credit")
        .path(EXPECTED_CREDIT_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(CREDIT_BODY)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(balanceBody())
        .toPact()

    @Test
    @PactTestFor(pactMethod = "debitPayerPact")
    fun `the payer debit response binds into BalanceResponse`(mockServer: MockServer) {
        assertClientPathsMatchContract()
        assertBindsToBalanceResponse(post(mockServer, EXPECTED_DEBIT_PATH, DEBIT_BODY))
    }

    @Test
    @PactTestFor(pactMethod = "creditPayeePact")
    fun `the payee credit response binds into BalanceResponse`(mockServer: MockServer) {
        assertClientPathsMatchContract()
        assertBindsToBalanceResponse(post(mockServer, EXPECTED_CREDIT_PATH, CREDIT_BODY))
    }

    private fun post(mockServer: MockServer, path: String, body: String): String = given()
        .baseUri(mockServer.getUrl())
        .contentType("application/json")
        .body(body)
        .post(path)
        .then()
        .statusCode(200)
        .extract().asString()

    /**
     * The half the provider replay cannot see: balance-service's payload must still bind into the
     * DTO the adapters actually deserialize into. This is the assertion that fails against the
     * pre-#8345 `BalanceResponse`, whose `availableBalance`/`currentBalance` were non-nullable and
     * absent from every real response.
     */
    private fun assertBindsToBalanceResponse(raw: String) {
        val response = mapper.readValue<BalanceResponse>(raw)
        assertThat(response.accountId).isNotNull()
        assertThat(response.currency).isEqualTo("CZK")
        // Present-and-parsed, not merely non-null-tolerant: the DTO's amounts are nullable so a
        // movement never fails on a body it does not consume, which would let a rename slip
        // through unnoticed if this assertion accepted null.
        assertThat(response.availableAmount).isNotNull()
        assertThat(response.bookedAmount).isNotNull()
    }

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the paths the
     * client would really call, recomputed from [BalanceRestClient]'s own annotations, must equal
     * the literals this pact promises balance-service. A `@Path` edit on the client reddens here.
     */
    private fun assertClientPathsMatchContract() {
        assertThat(clientDerivedPath("debit", PAYER_ACCOUNT_ID))
            .describedAs(PATH_DRIFT_MESSAGE)
            .isEqualTo(EXPECTED_DEBIT_PATH)
        assertThat(clientDerivedPath("credit", PAYEE_ACCOUNT_ID))
            .describedAs(PATH_DRIFT_MESSAGE)
            .isEqualTo(EXPECTED_CREDIT_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-settlement-service"
        const val PROVIDER = "openbank-balance-service"

        const val PATH_DRIFT_MESSAGE =
            "BalanceRestClient's @Path no longer produces the path this pact pins — either fix the " +
                "client or update the literal *and* re-verify against balance-service"

        /**
         * Seeded by balance-service's `a CZK balance exists for the settlement payer/payee account`
         * states. Retyped there, not shared: the two modules have no common test source set, and
         * the provider states state the same requirement from the other side.
         */
        const val PAYER_ACCOUNT_ID = "5e771e33-0000-4000-8000-00000000d1b1"
        const val PAYEE_ACCOUNT_ID = "5e771e33-0000-4000-8000-00000000c1e1"

        /** The settlement whose two legs these movements are; also the reference-id suffix. */
        const val SETTLEMENT_ID = "5e771e33-0000-4000-8000-000000005e77"

        /**
         * LITERALS, deliberately retyped from balance-service's `BalanceResource`
         * (`@Path("/api/v1/balances")` + `@POST @Path("/{accountId}/debit")` / `.../credit`).
         * Never derive these from the client — see the class KDoc.
         */
        const val EXPECTED_DEBIT_PATH = "/api/v1/balances/$PAYER_ACCOUNT_ID/debit"
        const val EXPECTED_CREDIT_PATH = "/api/v1/balances/$PAYEE_ACCOUNT_ID/credit"

        private val mapper = jacksonObjectMapper()

        /**
         * Serialized from the production DTO, exactly as `BalanceDebitAdapter`/`BalanceCreditAdapter`
         * build it — including `description`, which balance-service's `BalanceOperationRequest`
         * does not declare and its Jackson config tolerates.
         */
        private fun movementBody(kind: String) = mapper.writeValueAsString(
            MoneyMovementRequest(
                amount = BigDecimal("750.00"),
                currency = "CZK",
                referenceId = "settlement-$kind-$SETTLEMENT_ID",
                description = "Settlement ${kind.replaceFirstChar { it.uppercase() }} $SETTLEMENT_ID",
            ),
        )

        val DEBIT_BODY: String = movementBody("debit")
        val CREDIT_BODY: String = movementBody("credit")

        /**
         * balance-service answers a movement with its `Balance` aggregate. Type matchers on the
         * amounts — a debit changes them, and settlement does not read them; what it cannot
         * survive is a field being renamed or dropped, which is what the names here pin.
         */
        fun balanceBody() = newJsonBody { o ->
            o.uuid("accountId")
            o.stringValue("currency", "CZK")
            o.numberType("availableAmount", 9250.00)
            o.numberType("bookedAmount", 9250.00)
        }.build()

        fun clientDerivedPath(method: String, accountId: String): String {
            val base = BalanceRestClient::class.java.getAnnotation(Path::class.java).value
            val sub = BalanceRestClient::class.java.methods
                .single { it.name == method }
                .getAnnotation(Path::class.java)
                .value
            return (base + sub).replace("{accountId}", accountId)
        }
    }
}
