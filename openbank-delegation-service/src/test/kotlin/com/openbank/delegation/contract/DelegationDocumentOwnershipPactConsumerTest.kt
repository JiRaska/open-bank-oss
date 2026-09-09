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
import com.openbank.delegation.infrastructure.client.DocumentServiceRestClient
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Pins the document-service field that opens ADR-0232 D7 admission: `partyRef` is the immutable
 * owner binding compared with the authenticated grantor. Content and storage coordinates are not
 * consumed and deliberately absent from the contract.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-document-service", pactVersion = PactSpecVersion.V3)
class DelegationDocumentOwnershipPactConsumerTest {
    private companion object {
        const val DOCUMENT_ID = "77777777-8888-4999-8aaa-bbbbbbbbbbbb"
        const val OWNER_PARTY_ID = "88888888-9999-4aaa-8bbb-cccccccccccc"
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-document-service")
    fun getDocumentOwnerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a document owned by a known party exists")
        .uponReceiving("GET document metadata for delegation ownership verification")
        .path("/api/v1/documents/$DOCUMENT_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.stringValue("id", DOCUMENT_ID)
                body.stringValue("partyRef", OWNER_PARTY_ID)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getDocumentOwnerPact")
    fun `metadata exposes only the owner binding required by the gate`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(DocumentServiceRestClient::class.java, "getDocument", "id" to DOCUMENT_ID))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isEqualTo(DOCUMENT_ID)
        assertThat(body.getString("partyRef")).isEqualTo(OWNER_PARTY_ID)
    }
}
