// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.delegation.infrastructure.client.CardIssuanceRestClient
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the CARD half of the ownership gate
 * ([com.openbank.delegation.infrastructure.client.RestResourceOwnershipClient], ADR-0232 D7).
 * Same shape and same stakes as the account half: the grantor's id is compared to the card's
 * `partyId`, and any failure to read that field refuses the offer.
 *
 * Worth stating what this does NOT check, because the field name invites the assumption: a card
 * carries BOTH `partyId` (the cardholder) and `accountId` (the account it draws on), and
 * delegation-service compares the grantor against `partyId`. A card whose account belongs to
 * someone else is not something this gate — or this contract — has an opinion about.
 *
 * Expected path is a LITERAL; only the request is reflected off [CardIssuanceRestClient] (see
 * [ClientRoute]).
 *
 * Provider replay: `CardIssuancePactFolderProviderVerificationTest` (`@PactFolder`, runs on every
 * PR) — the first provider-side verification card-issuance has ever had.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-card-issuance-service", pactVersion = PactSpecVersion.V3)
class DelegationCardOwnershipPactConsumerTest {

    private companion object {
        // Fixed UUIDs — must match the @State seed in card-issuance's provider verification.
        const val CARD_ID = "0a0a0a0a-1b1b-4c2c-8d3d-4e4e4e4e4e4e"
        const val OWNER_PARTY_ID = "5f5f5f5f-6a6a-4b7b-8c8c-9d9d9d9d9d9d"
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-card-issuance-service")
    fun getCardOwnerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a card held by a known party exists")
        .uponReceiving("GET the card whose ownership is being verified")
        .path("/api/v1/cards/$CARD_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", CARD_ID)
                o.stringValue("partyId", OWNER_PARTY_ID)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getCardOwnerPact")
    fun `the card lookup returns the holding partyId the ownership gate compares`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(CardIssuanceRestClient::class.java, "getCard", "id" to CARD_ID))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isEqualTo(CARD_ID)
        assertThat(body.getString("partyId")).isEqualTo(OWNER_PARTY_ID)
    }
}
