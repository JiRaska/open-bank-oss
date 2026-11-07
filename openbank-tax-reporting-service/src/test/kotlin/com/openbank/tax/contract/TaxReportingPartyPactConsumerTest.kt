// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.tax.infrastructure.returns.crsfatca.PartyTaxFactsDto
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: tax-reporting-service -> party-service
 * `GET /api/v1/reporting/parties/{id}/tax-facts` (ADR-0340 B1, the TIN boundary).
 *
 * The response carries TINs, so this is also the contract that a provider regression leaking the
 * route to any other ROLE_API caller gets caught by: the 401 leg replays without any identity in
 * `PartyPactFolderProviderVerificationTest`. Literal expected path, reflected request path.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingPartyPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingPartyPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun taxFactsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the party's tax facts for the CRS FATCA assembly")
        .path(EXPECTED_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("partyId", "62000000-0000-0000-0000-000000000062")
                o.stringType("holderName", "Pact Tax Resident")
                o.integerType("profileVersion", 1)
                o.stringType("declaredAt", "2026-06-15T10:00:00Z")
                o.stringValue("crsStatus", "REPORTABLE")
                o.stringValue("fatcaStatus", "NON_US")
                // Array matching is ordered and pins EVERY declared element: the reportable DE
                // residency with its TIN first, the domestic CZ residency with an explicit null
                // TIN second — exactly what the boundary answers for the seeded profile.
                o.array("taxResidencies") { a ->
                    a.`object` { de ->
                        de.stringValue("country", "DE")
                        de.stringType("tin", "123456789")
                    }
                    a.`object` { cz ->
                        cz.stringValue("country", "CZ")
                        cz.nullValue("tin")
                    }
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET tax facts with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "taxFactsPact")
    fun `the tax facts decode into the adapter DTO`(mockServer: MockServer) {
        val body = given().baseUri(mockServer.getUrl()).get(EXPECTED_PATH).then().statusCode(200)
            .extract().asString()
        val facts = mapper.readValue<PartyTaxFactsDto>(body)
        assertThat(facts.partyId).isEqualTo(PARTY_ID)
        assertThat(facts.crsStatus).isEqualTo("REPORTABLE")
        assertThat(facts.taxResidencies).isNotEmpty
        assertThat(facts.taxResidencies[0].country).isEqualTo("DE")
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(EXPECTED_PATH).then().statusCode(401)
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-party-service"
        const val NEGATIVE_AUTH_STATE = "no valid caller identity is presented"

        /** Must match the @State in PartyPactFolderProviderVerificationTest and its broker twin. */
        const val STATE = "a reportable party has declared a tax-residency profile with a TIN"

        /** Dedicated fixture id — no other pact's state creates it. */
        val PARTY_ID: UUID = UUID.fromString("62000000-0000-0000-0000-000000000062")

        /** LITERAL, retyped from PartyTaxReportingResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/reporting/parties/62000000-0000-0000-0000-000000000062/tax-facts"
    }
}
