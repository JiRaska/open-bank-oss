// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.interest.infrastructure.client.InitiateTransactionRequest
import com.openbank.interest.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the **withholding-tax remittance debit**:
 * [com.openbank.interest.infrastructure.kafka.WithholdingRemittanceSettlementConsumer] posting
 * `POST /api/v1/transactions` to move a month's withheld tax to the finanční úřad (#999, ADR-0038).
 *
 * Issue #8345 — the coverage axis. `check-pact-provider-replay.py` guarantees every *committed*
 * pact is replayed before merge, but a money-path cross-service call with **no pact at all** is
 * structurally invisible to it. This was one of transaction-service's five uncovered consumers;
 * #8665 covered lending and standing-order, this covers the tail together with sdd and swift.
 *
 * ## What this pins that the existing contracts do not
 *
 * Transaction-service's committed pacts for this route cover `rail = DOMESTIC` with **no**
 * `instructionType` (domestic-payment), `rail = SEPA_INST` **with** `ONE_OFF` (sepa-instant),
 * `rail = SEPA` on a `TRANSFER` (sepa-payment), and a `CREDIT` with no rail at all
 * (account-service). The pair this consumer actually sends — `DOMESTIC` **and** `ONE_OFF` — appears
 * in none of them, and the two fields are orthogonal by construction (ADR-0103), so covering each
 * separately does not cover the combination.
 *
 * The wire shape differs too, in a way easy to miss: this consumer's DTO declares no
 * `targetAccountId` **field**, so the key is absent from the JSON entirely — where
 * domestic-payment's pact sends it present-and-null. Absent and null are different bytes, and the
 * provider's `targetAccountId` is what `SettlementScope.staysInTheBank` reads as `hasInternalPayee`.
 *
 * ## Why the response body pins `rail` and `instructionType`, not just `id` and `status`
 *
 * `TransactionResource.initiateTransaction` parses both with
 * `runCatching { PaymentRail.valueOf(it) }.getOrNull()` — an **unrecognised rail is silently
 * dropped to null and the route still answers 201**. So a pact that asserted only the status code
 * would stay green if `DOMESTIC` were renamed or removed from [com.openbank.libs.domain.payment.PaymentRail],
 * while every remittance thereafter booked with no rail: a no-op wearing the signal of success,
 * the shape CLAUDE.md's push-adapter bullet is about. `TransactionResponse` echoes both fields, so
 * asserting them as exact values (`stringValue`, never `stringType`) is what makes that
 * null-coalescing falsifiable at the provider replay — the only place it can be seen at all.
 *
 * ## The asymmetry that makes this falsifiable at the consumer layer
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290, re-measured on #8552 and #8665).
 * Deriving *both* sides is vacuous — the Pact mock server answers whatever the client asks for, so
 * expectation and request move together and the test stays green against a route that does not
 * exist (#2269). [assertClientPathMatchesContract] pins the client's annotations to the literal;
 * the provider replay (`TransactionPactFolderProviderVerificationTest`, which runs on every PR)
 * independently answers whether transaction-service still serves it.
 *
 * The request body is serialised from the REAL client DTO [InitiateTransactionRequest] rather than
 * retyped as a JSON string, so a field renamed on the mirror reddens here.
 *
 * IMPORTANT — regenerate on change: re-run this test
 * (`./gradlew :openbank-interest-service:test --tests "*WithholdingRemittanceTransactionPactConsumerTest*"`)
 * and commit the updated pact JSON in the same PR; `.github/workflows/pact-drift-check.yml` fails
 * the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class WithholdingRemittanceTransactionPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /**
     * Exactly what `WithholdingRemittanceSettlementConsumer.debitRequestFor` builds: the batch's
     * cash leg as a `DOMESTIC` / `ONE_OFF` debit off the bank's remittance source account, with the
     * finanční úřad deliberately unrepresented as an account (it is external, so the credit side is
     * the bank's clearing suspense).
     */
    private val remittanceDebit = InitiateTransactionRequest(
        idempotencyKey = "interest-withholding-$REMITTANCE_ID",
        type = "DEBIT",
        sourceAccountId = UUID.fromString(REMITTANCE_SOURCE_ACCOUNT_ID),
        amount = BigDecimal("18450.00"),
        currencyCode = "CZK",
        description = "Withholding tax remittance 2026-01 / FU_PRAHA",
        valueDate = "2026-02-20",
        rail = "DOMESTIC",
        instructionType = "ONE_OFF",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun withholdingRemittanceDebitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST the withholding-tax remittance as a DOMESTIC one-off debit")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(remittanceDebit))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                // stringValue, NOT stringType — a type matcher accepts "FAILED" as happily as
                // "COMPLETED" and proves nothing. transaction-service books this route
                // synchronously (measured on #2425).
                o.stringValue("status", "COMPLETED")
                // The two fields the provider silently null-coalesces on an unknown value. See the
                // class KDoc: this is the only assertion anywhere that can see that happen.
                o.stringValue("rail", "DOMESTIC")
                o.stringValue("instructionType", "ONE_OFF")
            }.build(),
        )
        .toPact()

    /**
     * ADR-0279 #3: a contract test that only pins the success path stays green when the provider
     * stops enforcing authz on this route. The M2M token interest-service presents is issued by
     * its own OIDC client and can be missing, expired or revoked independently of the request
     * body being otherwise identical to [remittanceDebit] — this pins that transaction-service
     * still answers 401 (not a silent 201) when it is.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST the withholding-tax remittance debit with a missing or expired token")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(remittanceDebit))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `rejects the remittance debit with 401 when the caller has no valid identity`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(remittanceDebit))
            .post(clientDerivedTransactionsPath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "withholdingRemittanceDebitPact")
    fun `the remittance debit is accepted and comes back stamped DOMESTIC one-off`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(remittanceDebit))
            // Reflected off the client, NOT retyped: this is the path the real client issues.
            .post(clientDerivedTransactionsPath())
            .then()
            // The one thing bookAndSettle branches on: a non-2xx-non-409 throws, the batch never
            // settles and a due tax remittance sits unpaid behind a DLQ record.
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
        assertThat(body.getString("rail")).isEqualTo("DOMESTIC")
        assertThat(body.getString("instructionType")).isEqualTo("ONE_OFF")
    }

    /**
     * The path the real client would call, recomputed from [TransactionServiceClient]'s own
     * annotations, must equal the literal this pact promises transaction-service. A `@Path` edit on
     * the client reddens here; whether transaction-service still serves that route is the provider
     * replay's job.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedTransactionsPath())
            .describedAs(
                "TransactionServiceClient's @Path no longer produces the path this pact pins — " +
                    "either fix the client or update EXPECTED_TRANSACTIONS_PATH *and* re-verify " +
                    "against transaction-service",
            )
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-interest-service"
        const val PROVIDER = "openbank-transaction-service"

        const val REMITTANCE_ID = "7c1f0a2e-1111-4222-8333-444444444444"
        const val REMITTANCE_SOURCE_ACCOUNT_ID = "cccccccc-1111-4222-8333-444444444444"

        /**
         * LITERAL, deliberately retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")` + a `@POST` carrying no sub-path). Never derive this
         * from the client — see the class KDoc.
         */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        fun clientDerivedTransactionsPath(): String {
            val base = TransactionServiceClient::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
            val method = TransactionServiceClient::class.java.methods
                .single { it.name == "initiateTransaction" }
                .getAnnotation(Path::class.java)
                ?.value
                .orEmpty()
            return base + method
        }
    }
}
