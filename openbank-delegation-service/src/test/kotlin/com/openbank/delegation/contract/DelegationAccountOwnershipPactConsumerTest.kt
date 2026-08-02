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
import com.openbank.delegation.infrastructure.client.AccountServiceRestClient
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the ACCOUNT half of the ownership gate
 * ([com.openbank.delegation.infrastructure.client.RestResourceOwnershipClient], ADR-0232 D7):
 * "does this grantor own that account?", asked of the only service that can answer.
 *
 * ## What the contract is actually protecting
 *
 * `verdictFor` compares `getAccount(resourceId).partyId` to the grantor and returns OWNED /
 * NOT_OWNED, and a `NotFoundException` is treated as NOT_OWNED. So the whole gate rests on one
 * field name — `partyId` — on one route. Rename it provider-side and Jackson throws (the DTO's
 * `partyId` is non-null), the `catch (Exception)` lands on UNVERIFIABLE, and every offer is
 * refused: fail-closed, but a total outage of the feature that nothing else would notice.
 *
 * SAVINGS_GOAL grants resolve through this same lookup — a savings goal is account metadata
 * (ADR-0153), so its resource id IS the owning account's id — which is why one account
 * interaction covers two of the six resource types.
 *
 * Expected path is a LITERAL; only the request is reflected off [AccountServiceRestClient] (see
 * [ClientRoute]). This is the interaction that made the reflection worth doing: account-service
 * spells the path param `{accountId}`, delegation-service's client spells it `{id}`, and neither
 * name reaches the wire — a consumer test that hard-codes both sides could not tell you whether
 * the client had been pointed at `/api/v1/accounts` at all.
 *
 * Provider replay: `AccountPactFolderProviderVerificationTest` (`@PactFolder`, runs on every PR).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class DelegationAccountOwnershipPactConsumerTest {

    private companion object {
        // Fixed UUIDs — must match the @State seed in account-service's provider verification.
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-account-service")
    fun getAccountOwnerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account whose ownership is being verified")
        .path("/api/v1/accounts/$ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", ACCOUNT_ID)
                o.stringValue("partyId", OWNER_PARTY_ID)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getAccountOwnerPact")
    fun `the account lookup returns the owning partyId the ownership gate compares`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(AccountServiceRestClient::class.java, "getAccount", "id" to ACCOUNT_ID))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isEqualTo(ACCOUNT_ID)
        assertThat(body.getString("partyId")).isEqualTo(OWNER_PARTY_ID)
    }
}
