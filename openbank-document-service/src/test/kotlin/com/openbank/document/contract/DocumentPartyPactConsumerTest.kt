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
import com.openbank.document.infrastructure.client.PartyAddressClientResponse
import com.openbank.document.infrastructure.client.PartyClient
import com.openbank.document.infrastructure.client.PartyClientResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the party read behind legal-document rendering: the RAMCOVA_SMLOUVA
 * template's `{{party.legalName}}` and `{{party.address}}` placeholders are filled from
 * `GET /api/v1/parties/{id}` (see `PartyClient`'s KDoc — rendering against an empty map shipped
 * "(the "Customer")" with a blank address on a real signed document).
 *
 * What a rename breaks, and in which silent direction:
 * - `legalName` binds non-null into [PartyClientResponse], so a rename fails to construct — but
 *   [com.openbank.document.infrastructure.client.PartyAdapter] catches ANY exception and renders
 *   the contract WITHOUT customer details, so the failure mode is a signed document missing the
 *   counterparty, not an error. The pact requires the field present as a string.
 * - the address fields are nullable with defaults, so a rename there binds as null and
 *   `PartyAddressClientResponse.format()` skips it — the same silent blank-address defect the
 *   client was built to fix. The pact requires line1/city/postalCode/countryCode present, and the
 *   test asserts the format() output they feed is a full address, not a dangling fragment.
 *
 * The provider state seeds a dedicated party with a full postal address
 * (`PartyPactFolderProviderVerificationTest.partyExistsWithRegisteredAddress`) — vop's seeded row
 * deliberately carries `address = null`. `line2` is intentionally NOT in the pact: it is nullable
 * on the wire and the seed sets it to null, which a string matcher would reject.
 *
 * Expected paths are LITERALS; only the outgoing request path is reflected off the client's
 * `@Path`, so a path drift in either direction fails here instead of at render time.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class DocumentPartyPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun partyWithAddressPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the party a legal contract is rendered against")
        .path(EXPECTED_PARTY_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", PACT_PARTY_ID)
                o.stringType("legalName", "Pact Verify Address Company a.s.")
                o.`object`("address") { a ->
                    a.stringValue("line1", "Pact Avenue 12")
                    a.stringValue("city", "Prague")
                    a.stringValue("postalCode", "110 00")
                    a.stringValue("countryCode", "CZ")
                }
            }.build(),
        )
        .toPact()

    /**
     * The absence leg: a party the bank does not hold must answer 404, which [PartyAdapter] turns
     * into "render without customer details" — a document without a counterparty is bad, but one
     * that INVENTS one is worse. Reuses the provider's existing "no party exists for the id" state;
     * the id is one no positive state seeds.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun partyUnknownPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_PARTY_STATE)
        .uponReceiving("GET a party id the bank does not hold")
        .path(EXPECTED_UNKNOWN_PATH)
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyWithAddressPact")
    fun `a party with a registered address binds with every field the contract render formats`(mockServer: MockServer) {
        assertThat(clientDerivedPath(PACT_PARTY_ID)).isEqualTo(EXPECTED_PARTY_PATH)
        val raw = given().baseUri(mockServer.getUrl())
            .get(clientDerivedPath(PACT_PARTY_ID)).then().statusCode(200).extract().asString()
        val party = mapper.readValue<PartyClientResponse>(raw)
        assertThat(party.legalName).isEqualTo("Pact Verify Address Company a.s.")
        assertThat(party.address).isNotNull
        val address = party.address ?: error("address bound null despite the pact matcher")
        assertThat(formatForTemplate(address)).isEqualTo("Pact Avenue 12, 110 00 Prague")
    }

    @Test
    @PactTestFor(pactMethod = "partyUnknownPact")
    fun `an unknown party is a 404 the adapter maps to no customer details`(mockServer: MockServer) {
        assertThat(clientDerivedPath(UNKNOWN_PARTY_ID)).isEqualTo(EXPECTED_UNKNOWN_PATH)
        given().baseUri(mockServer.getUrl()).get(clientDerivedPath(UNKNOWN_PARTY_ID)).then().statusCode(404)
    }

    /** Mirrors [com.openbank.document.infrastructure.client.PartyAdapter]'s private format(). */
    private fun formatForTemplate(address: PartyAddressClientResponse): String {
        val cityLine = listOfNotNull(
            address.postalCode?.takeIf { it.isNotBlank() },
            address.city?.takeIf { it.isNotBlank() },
        ).joinToString(" ").takeIf { it.isNotBlank() }
        return listOfNotNull(
            address.line1?.takeIf { it.isNotBlank() },
            address.line2?.takeIf { it.isNotBlank() },
            cityLine,
        ).joinToString(", ")
    }

    private fun clientDerivedPath(id: String): String {
        // PartyClient declares @Path only on the method (the interface has no class-level base) —
        // a base of "" is the honest reflection of that, and a class-level @Path added later is
        // picked up without this helper changing.
        val base = PartyClient::class.java.getAnnotation(Path::class.java)?.value ?: ""
        val sub = PartyClient::class.java.methods.single { it.name == "getById" }
            .getAnnotation(Path::class.java).value
        return base + sub.replace("{id}", id)
    }

    companion object {
        const val CONSUMER = "openbank-document-service"
        const val PROVIDER = "openbank-party-service"
        const val STATE = "a party exists with a registered address"

        /** The provider's existing absence state, served by its folder verification class. */
        const val NO_PARTY_STATE = "no party exists for the id"

        /** Must equal `PartyPactFolderProviderVerificationTest.DOCUMENT_ADDRESS_PARTY_ID`. */
        const val PACT_PARTY_ID = "e2e2e2e2-f3f3-4a4a-9b5b-c6c6c6c6c6c6"

        /** Seeded by no positive state — the existing absence state answers 404 for it. */
        const val UNKNOWN_PARTY_ID = "0d0d0d0d-1e1e-4f2f-8a3b-4c4c4c4c4c4c"

        /** LITERALS, retyped from party-service's `PartyResource` — never derived from the client. */
        const val EXPECTED_PARTY_PATH = "/api/v1/parties/$PACT_PARTY_ID"
        const val EXPECTED_UNKNOWN_PATH = "/api/v1/parties/$UNKNOWN_PARTY_ID"
    }
}
