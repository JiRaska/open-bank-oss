// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.tax.infrastructure.returns.crsfatca.AccountBalanceDto
import com.openbank.tax.infrastructure.returns.crsfatca.BalanceReportingClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer contract: tax-reporting-service -> balance-service
 * `GET /api/v1/balances/{accountId}/{currency}?asOf=` (ADR-0340 B, the period-end balance read).
 *
 * The CRS/FATCA line's `period_end_balance` is the statement point-in-time read — a renamed
 * `bookedAmount` would decode as null and report a zero balance for every reportable account.
 * The pact pins the same account id the provider state seeds (`d5d5...`), because the endpoint
 * answers 404 for an account it holds no balance row for.
 *
 * Reuses the existing provider state "a CZK balance exists for the balance account"
 * (billing-service's fee-waiver fixture): the seeded row has no ledger-projection events after
 * the asOf date, so the rewind degrades to the current balance — 5000.00 CZK. The 401 leg
 * replays in `BalanceNegativeAuthProviderVerificationTest`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingBalancePactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingBalancePactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun bookedAsOfPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the CZK balance as of the period end for the CRS FATCA line")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("asOf=$AS_OF")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.numberType("bookedAmount", 5000.00)
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the balance with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("asOf=$AS_OF")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "bookedAsOfPact")
    fun `the balance decodes into the adapter DTO`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(mockServer.getUrl())
            .queryParam("asOf", AS_OF)
            .get(clientPath()).then().statusCode(200).extract().asString()
        val balance = mapper.readValue<AccountBalanceDto>(body)
        assertThat(balance.bookedAmount.toPlainString()).isEqualTo("5000.0")
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl())
            .queryParam("asOf", AS_OF)
            .get(clientPath()).then().statusCode(401)
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-balance-service"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** The same fixture billing-service's fee-waiver contract replays against. */
        const val STATE = "a CZK balance exists for the balance account"

        /** Far in the past: the seeded row has no projection events after it, so rewind is exact. */
        const val AS_OF = "2000-01-31"

        /** Must equal BalancePactFolderProviderVerificationTest's SINGLE_ACCOUNT_ID (d5d5…). */
        const val EXPECTED_PATH = "/api/v1/balances/d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5/CZK"

        fun clientPath(): String {
            val base = BalanceReportingClient::class.java.getAnnotation(Path::class.java).value
            val sub = BalanceReportingClient::class.java.methods.single { it.name == "bookedAsOf" }
                .getAnnotation(Path::class.java).value
            return (base + sub)
                .replace("{accountId}", "d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5")
                .replace("{currency}", "CZK")
        }
    }
}
