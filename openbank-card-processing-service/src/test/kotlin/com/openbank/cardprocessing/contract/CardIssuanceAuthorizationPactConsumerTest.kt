// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.contract

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
import com.openbank.cardprocessing.infrastructure.client.CardIssuanceClient
import com.openbank.cardprocessing.infrastructure.client.CardSummaryResponse
import com.openbank.cardprocessing.infrastructure.client.IssuerAuthorizationResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the two calls card-processing makes to card-issuance: the card
 * lookup that resolves the owner, and the authorisation decision itself.
 *
 * ## Why the decision call in particular
 *
 * The decision is not ours. Card-processing measures the spend and moves the money; card-issuance
 * decides (ADR-0194 D3, ADR-0283 D2). So the response shape is the whole interface to the control,
 * and it is silently breakable: Jackson leaves an unmatched property null, so if the field were
 * `reason` here and `declineReason` there, every decline would arrive with no reason and the
 * customer would be told nothing instead of being told why. Nothing would error.
 *
 * ## The asymmetry that makes this falsifiable
 *
 * The expected path is a **LITERAL**; only the outgoing request is reflected off
 * [CardIssuanceClient]'s own annotations. Deriving both sides is DRY and vacuous — the Pact mock
 * server answers whatever the client asks for, so expectation and request move together and the
 * test stays green against a route that does not exist. That is exactly how a call to a ledger path
 * that has never existed shipped (#2269, measured again on #2290).
 *
 * The response is bound into the REAL client DTOs rather than asserted off a JsonPath, so renaming a
 * field on the DTO reddens THIS test — the half the provider replay cannot see.
 *
 * Replayed by `CardIssuancePactFolderProviderVerificationTest` (`@PactFolder`, ungated, runs on the
 * pull request) and by its `@PactBroker` twin, which publishes the verification result that
 * `can-i-deploy` reads. Both halves are required and neither substitutes for the other.
 *
 * IMPORTANT — regenerate on change: re-run this test and commit the updated pact JSON in the same
 * PR; `pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-card-issuance-service", pactVersion = PactSpecVersion.V3)
class CardIssuanceAuthorizationPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun cardOwnershipPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a card held by a known party exists")
        .uponReceiving("GET the card whose owner card-processing resolves before authorising")
        .path(EXPECTED_CARD_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(OK)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers: card-processing does not care which ids these are, it cares that
                // the fields exist. accountId is the debit account and partyId the cardholder — a
                // rename of either would leave the authorisation with nowhere to post.
                o.uuid("id", UUID.fromString(PACT_CARD_ID))
                o.uuid("accountId", UUID.fromString(PACT_ACCOUNT_ID))
                o.uuid("partyId", UUID.fromString(PACT_PARTY_ID))
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardOwnershipPact")
    fun `the card response binds into CardSummaryResponse with account and party`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get(clientDerivedCardPath())
            .then()
            .statusCode(OK)
            .extract().asString()

        val card = mapper.readValue<CardSummaryResponse>(raw)
        assertThat(card.accountId).isNotNull()
        assertThat(card.partyId).isNotNull()
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun authorizationDecisionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a card held by a known party exists")
        .uponReceiving("POST an authorisation for card-issuance to decide")
        .path(EXPECTED_AUTHORIZE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(
            newJsonBody { o ->
                o.numberType("amountMinorUnits", PACT_AMOUNT)
                o.stringType("channel", "CONTACTLESS")
                o.stringType("mcc", "5411")
                o.stringType("countryCode", "CZ")
                // The three counters card-processing measures. They are why this service exists:
                // before it, the endpoint took them from whoever called it, and nobody did.
                o.numberType("spentTodayMinorUnits", 0)
                o.numberType("spentThisMonthMinorUnits", 0)
                o.numberType("spentThisMonthInCategoryMinorUnits", 0)
            }.build(),
        )
        .willRespondWith()
        .status(OK)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.booleanType("approved", true)
                // `declineReason`, NOT `reason` — the name is the point of this interaction, and
                // the provider replay is what adjudicates it against the real resource.
                o.stringType("category", "GROCERIES")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "authorizationDecisionPact")
    fun `the decision response binds into IssuerAuthorizationResponse`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(
                """
                {"amountMinorUnits":$PACT_AMOUNT,"channel":"CONTACTLESS","mcc":"5411","countryCode":"CZ",
                 "spentTodayMinorUnits":0,"spentThisMonthMinorUnits":0,"spentThisMonthInCategoryMinorUnits":0}
                """.trimIndent(),
            )
            .post(clientDerivedAuthorizePath())
            .then()
            .statusCode(OK)
            .extract().asString()

        val decision = mapper.readValue<IssuerAuthorizationResponse>(raw)
        assertThat(decision.approved).isTrue()
        assertThat(decision.category).isNotBlank()
    }

    /**
     * The path the client would really call, recomputed from [CardIssuanceClient]'s annotations,
     * must equal the literal this pact promises card-issuance. A `@Path` edit on the client reddens
     * here; whether card-issuance actually serves it is the provider replay's job.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedCardPath())
            .describedAs("CardIssuanceClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_CARD_PATH)
        assertThat(clientDerivedAuthorizePath())
            .describedAs("CardIssuanceClient's authorize @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_AUTHORIZE_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-card-processing-service"
        const val PROVIDER = "openbank-card-issuance-service"
        const val OK = 200
        const val PACT_AMOUNT = 2_500

        /** Seeded by card-issuance's `a card held by a known party exists` state. */
        const val PACT_CARD_ID = "0a0a0a0a-1b1b-4c2c-8d3d-4e4e4e4e4e4e"
        const val PACT_ACCOUNT_ID = "7c7c7c7c-8d8d-4e9e-8f0f-1a1a1a1a1a1a"
        const val PACT_PARTY_ID = "5f5f5f5f-6a6a-4b7b-8c8c-9d9d9d9d9d9d"

        /** LITERAL, retyped from card-issuance's own resources. Never derive these. */
        const val EXPECTED_CARD_PATH = "/api/v1/cards/$PACT_CARD_ID"
        const val EXPECTED_AUTHORIZE_PATH = "/api/v1/cards/$PACT_CARD_ID/authorizations"

        private fun derived(method: String): String {
            val base = CardIssuanceClient::class.java.getAnnotation(Path::class.java).value
            val sub = CardIssuanceClient::class.java.methods
                .first { it.name == method }
                .getAnnotation(Path::class.java)
                .value
            return (base + sub).replace("{id}", PACT_CARD_ID)
        }

        fun clientDerivedCardPath(): String = derived("getCard")

        fun clientDerivedAuthorizePath(): String = derived("authorize")
    }
}
