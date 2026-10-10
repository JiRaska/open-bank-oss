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
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.infrastructure.returns.CatalogueParser
import com.openbank.tax.infrastructure.returns.pension.FundPeriodFiguresDto
import com.openbank.tax.infrastructure.returns.pension.ParticipantAggregatesDto
import com.openbank.tax.infrastructure.returns.pension.PensionReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer contract: tax-reporting-service -> pension-service
 * `GET /api/v1/pension/reporting/participant-aggregates` (#12425). Replayed by pension-service's
 * `PensionPactFolderProviderVerificationTest` (AS service-account-openbank-tax-reporting) and
 * `PensionReportingNegativeAuthProviderVerificationTest`. Literal expected path, reflected request
 * path; the decoded body goes through the real PSP 31-04 mapping.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingPensionPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingPensionPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun contributions(o: au.com.dius.pact.consumer.dsl.LambdaDslObject) {
        o.numberType("participant", 0)
        o.numberType("employer", 0)
        o.numberType("state", 0)
        o.numberType("transferIn", 0)
        o.numberType("total", 0)
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun aggregatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET participant aggregates for the third quarter of 2026")
        .path(EXPECTED_PATH)
        .query("periodStart=2026-07-01&periodEnd=2026-09-30")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("periodStart", "2026-07-01")
                o.stringValue("periodEnd", "2026-09-30")
                o.stringType("currency", "CZK")
                o.`object`("participants") { p ->
                    p.integerType("inForce", 0)
                    p.integerType("contributing", 0)
                    p.integerType("pensioners", 0)
                }
                o.`object`("contributionsYtd") { c -> contributions(c) }
                o.`object`("payoutsYtd") { p ->
                    p.numberType("total", 0)
                    p.integerType("cases", 0)
                    p.numberType("taxWithheld", 0)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET participant aggregates with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .query("periodStart=2026-07-01&periodEnd=2026-09-30")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "aggregatesPact")
    fun `the aggregates decode into the adapter DTO and assemble PSP 31-04`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(mockServer.getUrl())
            .queryParam("periodStart", "2026-07-01").queryParam("periodEnd", "2026-09-30")
            .get(clientPath()).then().statusCode(200).extract().asString()
        val aggregates = mapper.readValue<ParticipantAggregatesDto>(body)
        val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
        val definition = catalogue.definition("PSP31-04")!!
        val values = runBlocking {
            PensionReturnDataAdapter(FixedSources(aggregates))
                .fetch(
                    catalogue,
                    definition,
                    "company",
                    ReportingPeriod(Periodicity.QUARTER, LocalDate.parse("2026-09-30")),
                )
        }
        assertThat(values.keys).containsExactlyInAnyOrderElementsOf(definition.datapoints)
        assertThat(definition.validate(values)).isEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl())
            .queryParam("periodStart", "2026-07-01").queryParam("periodEnd", "2026-09-30")
            .get(clientPath()).then().statusCode(401)
    }

    private class FixedSources(private val aggregates: ParticipantAggregatesDto) : PensionReportingSources {
        override suspend fun fund(fundId: UUID, from: LocalDate, to: LocalDate): FundPeriodFiguresDto =
            error("not used by PSP 31-04")

        override suspend fun participants(from: LocalDate, to: LocalDate) = aggregates
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-pension-service"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** Must match PensionPactFolderProviderVerificationTest (openbank-pension-service). */
        const val STATE = "pension participant activity may exist for the third quarter of 2026"

        /** LITERAL, retyped from pension-service's ParticipantReportingResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/pension/reporting/participant-aggregates"

        fun clientPath(): String {
            val base = PensionReportingClient::class.java.getAnnotation(Path::class.java).value
            val sub = PensionReportingClient::class.java.methods.single { it.name == "participantAggregates" }
                .getAnnotation(Path::class.java).value
            return base + sub
        }
    }
}
