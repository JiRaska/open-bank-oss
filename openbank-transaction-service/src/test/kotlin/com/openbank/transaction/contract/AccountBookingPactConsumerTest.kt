// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.transaction.infrastructure.client.AccountBookingView
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Legacy reads remain valid before context enrichment. BranchPaymentRecoveryIT exercises the
 * positive branch-context transport and SQL pin. Existing Account @PactFolder states replay these
 * interactions without inventing attribution for historical accounts.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AccountBookingPactConsumerTest {
    private val accountId = "11111111-2222-4333-8444-555555555555"
    private val missingId = "11111111-2222-4333-8444-000000000999"

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-account-service")
    fun accountRead(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("Transaction reads immutable account identity before pinning a payment route")
        .path("/api/v1/accounts/$accountId").method("GET")
        .willRespondWith().status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody {
                it.stringValue("id", accountId)
                it.stringValue("currencyCode", "CZK")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-account-service")
    fun missingAccount(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the requested account")
        .uponReceiving("Transaction cannot obtain routing for a missing account")
        .path("/api/v1/accounts/$missingId").method("GET")
        .willRespondWith().status(404).toPact()

    @Test
    @PactTestFor(pactMethod = "accountRead")
    fun `legacy account identity does not fabricate a booking context`(server: MockServer) {
        val json = given().baseUri(server.getUrl()).get("/api/v1/accounts/$accountId")
            .then().statusCode(200).extract().asString()
        val account = ObjectMapper().registerKotlinModule().readValue(json, AccountBookingView::class.java)
        assertThat(account.id).isEqualTo(UUID.fromString(accountId))
        assertThat(account.bookingContext).isNull()
        assertThat(account.currencyCode).isEqualTo("CZK")
    }

    @Test
    @PactTestFor(pactMethod = "missingAccount")
    fun `missing account is a refusal rather than an empty routing fact`(server: MockServer) {
        given().baseUri(server.getUrl()).get("/api/v1/accounts/$missingId").then().statusCode(404)
    }
}
