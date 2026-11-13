// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.contract

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
import com.openbank.party.infrastructure.client.AccountPageBody
import com.openbank.party.infrastructure.client.AccountServiceClient
import com.openbank.party.infrastructure.client.AccountServiceRestClient
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: party-service -> account-service `GET /api/v1/accounts?partyId=`, the call the
 * ADR-0179 merge precondition guard paginates before it ever permits a party merge.
 *
 * Why this pact matters more than most: the guard is fail-CLOSED by design, but a WRONG-SHAPE
 * 200 deserializes to an empty page, `findOpenAccounts` returns nothing, and the guard silently
 * becomes fail-OPEN — a merge stranded funds on a retired identity is exactly what the guard
 * exists to prevent. The contract below pins the shape that keeps fail-closed honest:
 * `data` + `pagination` (not `items` + `pageInfo`), the row's identifier under `accountNumber`
 * (not `iban`), and `pagination.hasNextPage` deciding whether a second page is fetched.
 *
 * Replayed by account-service's `AccountPactFolderProviderVerificationTest` (git pacts, runs on
 * every PR per #2338); the 401 leg is replayed by `AccountNegativeAuthProviderVerificationTest`.
 *
 * - positive: one ACTIVE CZK account for the known party, single page — the guard returns its
 *   account number;
 * - empty page: a party with no accounts — the guard returns an empty list (a WRONG party id
 *   must never read as "owns something", and an empty page must never throw);
 * - no identity: 401 — and unlike the GDPR export adapter this guard does NOT catch, so the
 *   adapter itself must surface the refusal rather than read it as "no accounts".
 */
@ExtendWith(PactConsumerTestExt::class)
@TestMethodOrder(MethodOrderer.MethodName::class)
@PactTestFor(providerName = PartyAccountListPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class PartyAccountListPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listAccountsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET accounts for a party before a merge")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("partyId=$PARTY_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("data") { arr ->
                    arr.`object` { acc ->
                        acc.stringValue("id", ACCOUNT_ID)
                        acc.stringValue("accountNumber", IBAN)
                        acc.stringValue("status", "ACTIVE")
                        acc.stringValue("currencyCode", "CZK")
                    }
                }
                o.`object`("pagination") { p ->
                    p.booleanValue("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listAccountsEmptyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_ACCOUNTS_STATE)
        .uponReceiving("GET accounts for a party that owns none")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("partyId=$UNKNOWN_PARTY_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("data") { arr -> }
                o.`object`("pagination") { p ->
                    p.booleanValue("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listAccountsUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET accounts with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("partyId=$PARTY_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listAccountsPact")
    fun `the merge guard lists the party open accounts`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val guard = AccountServiceClient(clientFor(mockServer))
        assertThat(runBlocking { guard.findOpenAccounts(UUID.fromString(PARTY_ID)) }).containsExactly(IBAN)
    }

    @Test
    @PactTestFor(pactMethod = "listAccountsEmptyPact")
    fun `a party that owns nothing yields an empty guard result`(mockServer: MockServer) {
        val guard = AccountServiceClient(clientFor(mockServer))
        assertThat(runBlocking { guard.findOpenAccounts(UUID.fromString(UNKNOWN_PARTY_ID)) }).isEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "listAccountsUnauthenticatedPact")
    fun `a refused account list propagates and never reads as empty`(mockServer: MockServer) {
        val guard = AccountServiceClient(clientFor(mockServer))
        assertThatThrownBy { runBlocking { guard.findOpenAccounts(UUID.fromString(PARTY_ID)) } }
            .isInstanceOfSatisfying(WebApplicationException::class.java) { e ->
                assertThat(e.response?.status).isEqualTo(401)
            }
    }

    /** The real guard over an anonymous [AccountServiceRestClient] bound to the pact mock server. */
    private fun clientFor(mockServer: MockServer): AccountServiceRestClient = object : AccountServiceRestClient {
        override fun listByParty(partyId: UUID, limit: Int, cursor: String?): Uni<AccountPageBody> =
            Uni.createFrom().item {
                val request = given().baseUri(mockServer.getUrl()).contentType("application/json")
                    .queryParam("partyId", partyId.toString())
                    .queryParam("limit", limit)
                if (cursor != null) request.queryParam("cursor", cursor)
                val response = request.get(EXPECTED_PATH)
                if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
                mapper.readValue<AccountPageBody>(response.asString())
            }
    }

    companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val CONSUMER = "openbank-party-service"
        const val PROVIDER = "openbank-account-service"

        /** Reuses AccountPactFolderProviderVerificationTest's seeded ACTIVE account. */
        const val STATE = "an account owned by a known party exists"
        const val NO_ACCOUNTS_STATE = "no account exists for the requested party"

        /** Must match ACCOUNT_ID / OWNER_PARTY_ID in AccountPactFolderProviderVerificationTest. */
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val UNKNOWN_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaab"
        const val IBAN = "CZ6508000000192000145399"
        const val PAGE_SIZE = "100"

        /** LITERAL, retyped from account-service's AccountResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/accounts"

        fun clientPath(): String {
            val base = AccountServiceRestClient::class.java.getAnnotation(Path::class.java).value
            return base
        }
    }
}
