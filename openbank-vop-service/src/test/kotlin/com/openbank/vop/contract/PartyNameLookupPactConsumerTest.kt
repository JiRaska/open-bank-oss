// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.contract

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
import com.openbank.vop.infrastructure.client.PartyServiceClient
import com.openbank.vop.infrastructure.client.PartySummary
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for hop 2 of the ADR-0171 §4 VoP name resolution: `GET
 * /api/v1/parties/{id}`, the call that produces the authoritative payee name VoP compares against.
 * The generated pact is committed to `pacts/openbank-vop-service-openbank-party-service.json`
 * (git-pact, ADR-0063) and replayed by `PartyEventPactProviderVerificationTest` in
 * openbank-party-service — the single `@Provider("openbank-party-service")` class in the repo.
 *
 * Why hop 2 first: see `AccountHolderNameLookupAdapter`. Both hops are real HTTP, but
 * party-service is where the *answer* comes from — a renamed or dropped `legalName`/`tradingName`
 * turns every verification into NO_DATA while every unit test stays green (the port is mocked
 * there). Hop 1 (account-service `GET /api/v1/accounts/iban/{iban}`) was left unpinned at the time
 * because account-service's only `@Provider` class was message-only (`MessageTestTarget`, no
 * Quarkus boot), so an HTTP interaction had nowhere to be replayed. That is no longer the case —
 * `AccountPactFolderProviderVerificationTest` boots Quarkus and dispatches per interaction — and
 * hop 1 is now pinned by [AccountIbanLookupPactConsumerTest] (#8345).
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290). Deriving *both* sides is vacuous: the
 * Pact mock server answers whatever the client asks for, so expectation and request would move
 * together and the test would stay green against a route that does not exist — exactly how #2269
 * shipped a finrep call to a ledger path that never existed. Here [assertClientPathMatchesContract]
 * pins the client's annotations to the literal, so changing [PartyServiceClient]'s `@Path` reddens
 * this test, and the provider replay independently adjudicates whether party-service serves it.
 *
 * The response is bound into the REAL client DTO [PartySummary] rather than asserted off a
 * JsonPath, so renaming a field on the DTO reddens THIS test — the half of the contract the
 * provider replay cannot see.
 *
 * `tradingName` is pinned as a present string, not omitted: the adapter falls back to it when
 * `legalName` is blank, so "party-service still sends this field" is load-bearing.
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-vop-service:test --tests
 * "*PartyNameLookupPactConsumerTest*"`) and commit the updated pact JSON in the same PR;
 * `.github/workflows/pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class PartyNameLookupPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun partyWithLegalAndTradingNamePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a party exists with both a legal name and a trading name")
        .uponReceiving("GET the party whose name VoP compares the payer-supplied name against")
        .path(EXPECTED_PARTY_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers: VoP compares whatever name it is given, it does not care about the
                // value. What it cannot survive is the field being renamed or absent.
                o.stringType("legalName", "Pact Verify Trading Company a.s.")
                o.stringType("tradingName", "PactVerify")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyWithLegalAndTradingNamePact")
    fun `the party response binds into PartySummary with both name fields`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedPartyPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val summary = mapper.readValue<PartySummary>(raw)
        assertThat(summary.legalName).isNotBlank()
        assertThat(summary.tradingName).isNotBlank()
    }

    /**
     * The asymmetry that makes this contract falsifiable at the consumer layer: the path the client
     * would really call, recomputed from [PartyServiceClient]'s own annotations, must equal the
     * literal this pact promises party-service. A `@Path` edit on the client reddens here.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedPartyPath())
            .describedAs(
                "PartyServiceClient's @Path no longer produces the path this pact pins — either fix " +
                    "the client or update EXPECTED_PARTY_PATH *and* re-verify against party-service",
            )
            .isEqualTo(EXPECTED_PARTY_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-vop-service"
        const val PROVIDER = "openbank-party-service"

        /** Seeded by party-service's `a party exists with both a legal name and a trading name` state. */
        const val PACT_PARTY_ID = "b1b1b1b1-c2c2-4d4d-8e8e-f9f9f9f9f9f9"

        /**
         * LITERAL, deliberately retyped from party-service's `PartyResource` (`@Path("/api/v1/parties")`
         * + `@GET @Path("/{id}")`). Never derive this from the client — see the class KDoc.
         */
        const val EXPECTED_PARTY_PATH = "/api/v1/parties/$PACT_PARTY_ID"

        fun clientDerivedPartyPath(): String {
            val base = PartyServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = PartyServiceClient::class.java.methods
                .single { it.name == "getParty" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{id}", PACT_PARTY_ID)
        }
    }
}
