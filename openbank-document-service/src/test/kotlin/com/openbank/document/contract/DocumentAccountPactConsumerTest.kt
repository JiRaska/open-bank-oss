// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

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
import com.openbank.document.infrastructure.client.AccountClient
import com.openbank.document.infrastructure.client.AccountPageClientResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the party-scoped account list behind the framework agreement's
 * Article 2 (`{{account.iban}}` / `{{product.*}}` clause): by the time RAMCOVA_SMLOUVA is signed
 * the account already exists (ADR-0162 D7), so the clause renders real on-file data fetched via
 * `GET /api/v1/accounts?partyId=…` — see `AccountClient`'s KDoc.
 *
 * What a drift breaks, and in which silent direction — [AccountAdapter.findCurrentAccount] catches
 * ANY exception and renders the contract WITHOUT the account clause, so none of these reach an
 * error surface:
 * - `productId` that is not a UUID string: `UUID.fromString` throws inside the adapter's try,
 *   the whole account is dropped. The pact requires a uuid-typed productId.
 * - a renamed `accountNumber`/`accountType`/`status`: the non-null data-class binding construct-
 *   fails the same way. The pact requires all three.
 * - a renamed `data`: [AccountPageClientResponse.data] defaults to emptyList(), so the page binds
 *   "successfully" with zero rows and no CURRENT account is found — the pact requires at least
 *   one row.
 *
 * The provider state is the one account-service already seeds for billing's fee-pricing pact
 * (`AccountPactFolderProviderVerificationTest.accountOwnedByKnownParty`): a CURRENT account of
 * the known party — exactly the shape Article 2 names, and SAVINGS would not do (the adapter's
 * CURRENT filter exists because #1477 opens both; the pact proves the filter has a row to find).
 *
 * Expected paths are LITERALS; only the outgoing request path is reflected off the client's
 * `@Path`, so a path drift in either direction fails here instead of at render time.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class DocumentAccountPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun currentAccountListPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the party's accounts to name the payment account in a contract")
        .path(EXPECTED_PATH)
        .query("partyId=$OWNER_PARTY_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("data", 1) { a ->
                    a.uuid("id", UUID.fromString(PACT_ACCOUNT_ID))
                    a.stringValue("accountNumber", PACT_IBAN)
                    a.stringValue("accountType", "CURRENT")
                    a.uuid("productId", UUID.fromString(PACT_PRODUCT_ID))
                    a.stringValue("status", "ACTIVE")
                }
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the list must answer 401 before the
     * handler runs — otherwise the adapter's catch would render a contract with a silently
     * omitted account clause that a real render would have filled. Replayed by
     * `AccountNegativeAuthProviderVerificationTest`, which boots the provider without a test
     * identity; the positive twin filters this state out for the same reason as billing's.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun accountListUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET a party's accounts with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .query("partyId=$OWNER_PARTY_ID")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "currentAccountListPact")
    fun `a party's account list binds with the CURRENT row the contract clause renders`(mockServer: MockServer) {
        assertThat(clientDerivedPath()).isEqualTo(EXPECTED_PATH)
        val raw = given().baseUri(mockServer.getUrl()).queryParam("partyId", OWNER_PARTY_ID)
            .get(clientDerivedPath()).then().statusCode(200).extract().asString()
        val page = mapper.readValue<AccountPageClientResponse>(raw)
        assertThat(page.data).isNotEmpty
        // The mapping the adapter performs: accountNumber -> IBAN clause, productId -> UUID
        // (UUID.fromString is what throws — inside the adapter's catch — when this is not a UUID).
        val current = page.data.first { it.accountType == "CURRENT" }
        assertThat(current.accountNumber).isEqualTo(PACT_IBAN)
        assertThat(UUID.fromString(current.productId)).isEqualTo(UUID.fromString(PACT_PRODUCT_ID))
    }

    @Test
    @PactTestFor(pactMethod = "accountListUnauthenticatedPact")
    fun `an account list read with no identity is refused with 401, never rendered as absent`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).queryParam("partyId", OWNER_PARTY_ID)
            .get(clientDerivedPath()).then().statusCode(401)
    }

    private fun clientDerivedPath(): String {
        // AccountClient declares @Path only on the method (the interface has no class-level base).
        val base = AccountClient::class.java.getAnnotation(Path::class.java)?.value ?: ""
        val sub = AccountClient::class.java.methods.single { it.name == "listByParty" }
            .getAnnotation(Path::class.java).value
        return base + sub
    }

    companion object {
        const val CONSUMER = "openbank-document-service"
        const val PROVIDER = "openbank-account-service"

        /** The provider's state seeded by `AccountPactFolderProviderVerificationTest`. */
        const val STATE = "an account owned by a known party exists"

        /** The provider's NEGATIVE_AUTH_STATE, served by `AccountNegativeAuthProviderVerificationTest`. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** Must equal the ids `AccountPactFolderProviderVerificationTest` seeds for this state. */
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val PACT_ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PACT_PRODUCT_ID = "99999999-8888-4777-8666-555555555555"
        const val PACT_IBAN = "CZ6508000000192000145399"

        /** LITERAL, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/accounts"
    }
}
