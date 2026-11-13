// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.contract

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
import com.openbank.clients.account.AccountClient
import com.openbank.clients.account.AccountLookup
import com.openbank.standingorder.infrastructure.client.AccountServiceClient
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Path
import jakarta.ws.rs.client.ClientBuilder
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.InputStream
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.util.UUID

/**
 * Consumer-driven contract for the **creditor IBAN lookup** a due standing order runs before it
 * books an internal transfer: `StandingOrderDueConsumer` calls `GET /api/v1/accounts/iban/{iban}`
 * and reads `id` (the transfer's target account) and `partyId` from the body (issue #8345).
 *
 * Both outcomes are load-bearing, so both are pinned:
 *  * **200** — the IBAN is internal; `id` must parse as a UUID or the transfer has no target.
 *  * **404** — the IBAN is not held here. The consumer treats any non-2xx as "not internal" and
 *    records the execution as failed; a provider answering 200 with nulls instead would slip past
 *    that check and book against no account.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290). The states are the ones account-service already seeds for
 * vop-service's identical lookup, so the IBANs below must match those.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-account-service", pactVersion = PactSpecVersion.V3)
class CreditorIbanLookupPactConsumerTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun internalCreditorPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an account owned by a known party exists")
        .uponReceiving("GET the account behind a standing order's creditor IBAN")
        .path("$EXPECTED_IBAN_PATH_PREFIX$KNOWN_IBAN")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(OWNER_PARTY_ID))
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun externalCreditorPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no account exists for the IBAN")
        .uponReceiving("GET the account behind a creditor IBAN the bank does not hold")
        .path("$EXPECTED_IBAN_PATH_PREFIX$UNKNOWN_IBAN")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "internalCreditorPact")
    fun `an internal creditor IBAN resolves to a target account id and its owning party`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val lookup = facadeFor(mockServer).getAccountByIban(KNOWN_IBAN).await().indefinitely()

        // Read exactly as StandingOrderDueConsumer reads it: the generated account's id and partyId.
        val account = (lookup as AccountLookup.Found).account
        assertThat(account.id).isEqualTo(UUID.fromString(ACCOUNT_ID))
        assertThat(account.partyId).isEqualTo(UUID.fromString(OWNER_PARTY_ID))
    }

    @Test
    @PactTestFor(pactMethod = "externalCreditorPact")
    fun `an IBAN the bank does not hold answers 404, not an empty account`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val lookup = facadeFor(mockServer).getAccountByIban(UNKNOWN_IBAN).await().indefinitely()

        assertThat(lookup).isEqualTo(AccountLookup.NotFound)
    }

    /**
     * The real facade over the generated interface, bound to the pact mock by a real JAX-RS client. The
     * outgoing path is reflected off the generated `@Path` annotations while the pact pins the literal.
     */
    private fun facadeFor(mockServer: MockServer): AccountClient {
        val http = ClientBuilder.newBuilder().register(JsonReader(mapper)).build()
        val rest = Proxy.newProxyInstance(
            AccountServiceClient::class.java.classLoader,
            arrayOf(AccountServiceClient::class.java),
        ) { _, method, args ->
            check(method.name == "getAccountByIban") { "unexpected account-service operation ${method.name}" }
            Uni.createFrom().item {
                http.target(mockServer.getUrl()).path(clientDerivedIbanPath(args!![0] as String))
                    .request(MediaType.APPLICATION_JSON).get()
            }
        } as AccountServiceClient
        return AccountClient(rest)
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

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedIbanPath(KNOWN_IBAN))
            .describedAs(
                "AccountServiceClient's @Path no longer produces the path this pact pins — fix the " +
                    "client or update EXPECTED_IBAN_PATH_PREFIX *and* re-verify against account-service",
            )
            .isEqualTo("$EXPECTED_IBAN_PATH_PREFIX$KNOWN_IBAN")
    }

    private companion object {
        const val CONSUMER = "openbank-standing-order-service"
        const val PROVIDER = "openbank-account-service"

        /** Must match AccountPactFolderProviderVerificationTest's seeded account. */
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val KNOWN_IBAN = "CZ6508000000192000145399"

        /** An IBAN no provider state seeds — the same one vop-service's negative case uses. */
        const val UNKNOWN_IBAN = "CZ6508000000192000145981"

        /** LITERAL, retyped from account-service's `AccountResource` — never derived from the client. */
        const val EXPECTED_IBAN_PATH_PREFIX = "/api/v1/accounts/iban/"

        fun clientDerivedIbanPath(iban: String): String {
            val operation = AccountServiceClient::class.java.methods.single { it.name == "getAccountByIban" }
            val base = operation.declaringClass.getAnnotation(Path::class.java).value
            val method = operation.getAnnotation(Path::class.java).value
            return (base + method).replace("{iban}", iban)
        }
    }
}
