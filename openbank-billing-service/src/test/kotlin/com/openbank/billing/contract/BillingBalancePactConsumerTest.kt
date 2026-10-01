// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.contract

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
import com.openbank.billing.infrastructure.client.BalanceDto
import com.openbank.billing.infrastructure.client.BalanceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal

/**
 * Consumer-driven contract for the **balance read behind balance-conditioned fee waivers**:
 * `RestAccountContextPort.resolve` calling `GET /api/v1/balances/{accountId}/{currency}` and reading
 * `bookedAmount` (issue #8345). This is the contract that found the defect it now guards:
 * [BalanceDto] demanded a `currentBalance` the provider never sends, so the adapter always resolved
 * "no balance" and swallowed the binding failure (#11650).
 *
 * The provider state is the one balance-service already seeds for account-service's read contract.
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class BillingBalancePactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun balancePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the balance account")
        .uponReceiving("GET the CZK balance a fee waiver is evaluated against")
        .path(EXPECTED_BALANCE_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("accountId", PACT_ACCOUNT_ID)
                o.stringValue("currency", "CZK")
                o.decimalType("bookedAmount", 5000.00)
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `BalanceNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun balanceUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET a balance for a fee waiver with no M2M identity is refused")
        .path(EXPECTED_BALANCE_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "balanceUnauthenticatedPact")
    fun `a balance read with no identity is refused with 401, never evaluated`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedBalancePath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "balancePact")
    fun `the balance binds into BalanceDto with its booked amount`(mockServer: MockServer) {
        assertThat(clientDerivedBalancePath())
            .describedAs("BalanceRestClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_BALANCE_PATH)
        val raw = given().baseUri(mockServer.getUrl())
            .get(clientDerivedBalancePath()).then().statusCode(200).extract().asString()
        val dto = mapper.readValue<BalanceDto>(raw)
        assertThat(dto.bookedAmount).isGreaterThanOrEqualTo(BigDecimal.ZERO)
        assertThat(dto.currency).isEqualTo("CZK")
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-billing-service"
        const val PROVIDER = "openbank-balance-service"

        /** balance-service's SINGLE_ACCOUNT_ID in BalancePactFolderProviderVerificationTest. */
        const val PACT_ACCOUNT_ID = "d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5"

        /** LITERAL, retyped from balance-service's `BalanceResource` — never derived from the client. */
        const val EXPECTED_BALANCE_PATH = "/api/v1/balances/$PACT_ACCOUNT_ID/CZK"

        fun clientDerivedBalancePath(): String {
            val base = BalanceRestClient::class.java.getAnnotation(Path::class.java).value
            val method = BalanceRestClient::class.java.methods.single { it.name == "getBalance" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID).replace("{currency}", "CZK")
        }
    }
}
