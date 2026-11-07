// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.aml.infrastructure.client.AccountServiceClient
import com.openbank.aml.infrastructure.client.AccountServiceRestClient
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.client.ClientBuilder
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyReader
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.InputStream
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.util.UUID

/**
 * Consumer-driven contract for the party-resolution read the #3413 sweep makes against
 * account-service (`AccountServiceRestClient.getById`, ADR-0063). The sweep resolves the
 * owning party of an account AFTER a case is recorded — a case must never fail because
 * account-service is unreachable — so the contract pins the two shapes the wrapper
 * branches on: a 200 carrying `partyId`, and a 404 meaning "unknown owner, resolve later".
 *
 * The wrapper swallows every non-2xx into `null`; the tests assert that behaviour, not
 * just the wire. Since #13169 the wire call is the GENERATED `getAccount` (`openbank-client-account`)
 * classified by its `AccountClient` facade; the test drives both over a real JAX-RS client, so the
 * path below must stay a LITERAL — the asymmetry is the test. Provider verification lives in account-service's
 * AccountPactFolderProviderVerificationTest (the 200 leg reuses its seeded
 * `an account owned by a known party exists` state; the 404 leg sends a UUID the seed
 * never creates, so the provider's real not-found path answers).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class AmlAccountOwnershipPactConsumerTest {

    @Pact(consumer = "openbank-aml-service", provider = "openbank-account-service")
    fun accountOwnerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account whose owning party the AML sweep resolves")
        .path("/api/v1/accounts/$ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Only what the sweep consumes (id + partyId) — the pact
            // must not pin the other fifteen AccountResponse fields against a consumer that
            // never reads them.
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(OWNER_PARTY_ID))
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-aml-service", provider = "openbank-account-service")
    fun accountMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the requested account")
        .uponReceiving("GET an account the AML sweep has no record of")
        .path("/api/v1/accounts/$MISSING_ACCOUNT_ID")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountOwnerPact")
    fun `the sweep resolves the owning party of a known account`(mockServer: MockServer) {
        val owner = runBlocking {
            clientFor(mockServer).findPartyByAccountId(UUID.fromString(ACCOUNT_ID))
        }
        assertThat(owner).isEqualTo(UUID.fromString(OWNER_PARTY_ID))
    }

    @Test
    @PactTestFor(pactMethod = "accountMissingPact")
    fun `a missing account resolves to null without failing the sweep`(mockServer: MockServer) {
        val owner = runBlocking {
            clientFor(mockServer).findPartyByAccountId(UUID.fromString(MISSING_ACCOUNT_ID))
        }
        assertThat(owner).isNull()
    }

    /**
     * The real [AccountServiceClient] wrapper around the generated interface, bound to the pact mock by a
     * real JAX-RS client. Only `getAccount` is answered: any other generated operation the wrapper might
     * start calling fails the test loudly instead of passing against a route the pact does not record.
     */
    private fun clientFor(mockServer: MockServer): AccountServiceClient {
        val http = ClientBuilder.newBuilder().register(JsonReader(mapper)).build()
        val rest = Proxy.newProxyInstance(
            AccountServiceRestClient::class.java.classLoader,
            arrayOf(AccountServiceRestClient::class.java),
        ) { _, method, args ->
            check(method.name == "getAccount") { "unexpected account-service operation ${method.name}" }
            Uni.createFrom().item {
                http.target(mockServer.getUrl()).path("/api/v1/accounts/${args!![0]}")
                    .request(MediaType.APPLICATION_JSON).get()
            }
        } as AccountServiceRestClient
        return AccountServiceClient(rest)
    }

    private class JsonReader(private val mapper: ObjectMapper) : MessageBodyReader<Any> {
        override fun isReadable(
            type: Class<*>,
            genericType: Type?,
            annotations: Array<out Annotation>?,
            mediaType: MediaType?,
        ) = !InputStream::class.java.isAssignableFrom(type)

        override fun readFrom(
            type: Class<Any>,
            genericType: Type?,
            annotations: Array<out Annotation>?,
            mediaType: MediaType?,
            httpHeaders: MultivaluedMap<String, String>?,
            entityStream: InputStream,
        ): Any = mapper.readValue(entityStream, type)
    }

    private companion object {
        val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        /** Must match AccountPactFolderProviderVerificationTest's ACCOUNT_ID / OWNER_PARTY_ID. */
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"

        /** Deliberately never seeded on the provider side — the 404 leg leans on that. */
        const val MISSING_ACCOUNT_ID = "7d7d7d7d-0000-4000-8000-7d7d7d7d7d7d"
    }
}
