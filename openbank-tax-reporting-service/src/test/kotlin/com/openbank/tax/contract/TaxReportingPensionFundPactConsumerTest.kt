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
import com.openbank.tax.infrastructure.returns.pension.PensionFundReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import com.openbank.tax.infrastructure.returns.pension.VerifiedFundClose
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer contract: tax-reporting-service -> pension-fund-service
 * `GET /api/v1/reporting/funds/{fundId}/period-figures` (#12425). Replayed by pension-fund-service's
 * `PensionFundPactFolderProviderVerificationTest` (AS service-account-openbank-tax-reporting) and its
 * negative-auth twin.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`,
 * so a client pointed at a route that does not exist fails here (#2290). The response is decoded
 * with the adapter's own DTO and pushed through the real catalogue mapping, so a renamed field
 * breaks the consumer and a missing datapoint breaks the assembly.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingPensionFundPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingPensionFundPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun periodFiguresPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET September 2026 period figures of a pension fund")
        .path(EXPECTED_PATH)
        .query("periodStart=2026-09-01&periodEnd=2026-09-30")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("fundId", FUND_ID)
                o.stringType("currency", "CZK")
                o.stringValue("periodStart", "2026-09-01")
                o.stringValue("periodEnd", "2026-09-30")
                o.stringType("closingValuationDate", "2026-09-30")
                o.`object`("balanceSheet") { b ->
                    b.numberType("totalAssets", 1000)
                    b.numberType("totalLiabilities", 0)
                    b.numberType("totalEquity", 1000)
                }
                o.`object`("profitAndLossYtd") { p ->
                    p.numberType("revaluationGains", 0)
                    p.numberType("revaluationLosses", 0)
                    p.numberType("otherInvestmentResult", 1000)
                    p.numberType("managementFees", 0)
                    p.numberType("profitLoss", 1000)
                }
                o.`object`("units") { u ->
                    u.numberType("opening", 0)
                    u.numberType("issued", 1000)
                    u.numberType("cancelled", 0)
                    u.numberType("closing", 1000)
                    u.numberType("unitValue", 1)
                    u.numberType("unitValuePeriodMax", 1)
                }
                o.`object`("portfolio") { p ->
                    p.numberType("carryingValue", 1000)
                    p.integerType("holdingsCount", 1)
                    p.numberType("cash", 0)
                    p.numberType("loansOutstanding", 0)
                    p.integerType("unclassifiedCount", 0)
                }
                o.`object`("entitlements") { e ->
                    e.numberType("opening", 0)
                    e.numberType("increase", 1000)
                    e.numberType("decrease", 0)
                    e.numberType("closing", 1000)
                }
                o.`object`("participants") { p ->
                    p.integerType("holders", 1)
                    p.integerType("subscribing", 1)
                }
                o.stringType("fingerprint", "0f0e")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET fund period figures with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .query("periodStart=2026-09-01&periodEnd=2026-09-30")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun sealedClosePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(SEALED_STATE)
        .uponReceiving("GET an explicitly selected immutable fund reporting close")
        .path(SEALED_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(
            mapOf(
                "Content-Type" to "application/json",
                "X-Reporting-Close-Id" to SEALED_CLOSE_ID,
                "X-Reporting-Payload-Schema-Version" to "1.3.0",
            ),
        )
        .matchHeader("X-Reporting-Close-Revision", "[1-9][0-9]*", "1")
        .matchHeader("X-Reporting-Payload-SHA256", "[0-9a-f]{64}", "a".repeat(64))
        .body(
            newJsonBody { o ->
                o.stringValue("fundId", SEALED_FUND_ID)
                o.stringValue("currency", "CZK")
                o.stringValue("periodStart", "2014-01-01")
                o.stringValue("periodEnd", "2014-03-31")
                o.stringType("closingNavId", "8c16d486-14f1-48e4-aace-acf956cbf38b")
                o.stringValue("closingValuationDate", "2014-03-31")
                o.`object`("balanceSheet") { b ->
                    b.numberType("totalAssets", 1)
                    b.numberType("totalLiabilities", 0)
                    b.numberType("totalEquity", 1)
                }
                o.`object`("profitAndLossYtd") { p ->
                    p.numberType("managementFees", 0)
                    p.numberType("profitLoss", 0)
                }
                o.`object`("units") { u ->
                    u.numberType("opening", 0)
                    u.numberType("issued", 0)
                    u.numberType("cancelled", 0)
                    u.numberType("closing", 0)
                    u.numberType("unitValue", 0)
                    u.numberType("unitValuePeriodMax", 0)
                }
                o.`object`("portfolio") { }
                o.`object`("entitlements") { e ->
                    e.numberType("opening", 0)
                    e.numberType("increase", 0)
                    e.numberType("decrease", 0)
                    e.numberType("closing", 0)
                }
                o.`object`("participants") { p ->
                    p.integerType("holders", 0)
                    p.integerType("subscribing", 0)
                }
                o.array("basisNavIds") { ids -> ids.stringType("8c16d486-14f1-48e4-aace-acf956cbf38b") }
                o.stringType("fingerprint", "a".repeat(64))
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun sealedCloseNoAuthPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET an immutable fund close without M2M identity is refused")
        .path(SEALED_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "periodFiguresPact")
    fun `the fund figures decode into the adapter DTO and assemble every sourced fund return`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(mockServer.getUrl())
            .queryParam("periodStart", "2026-09-01").queryParam("periodEnd", "2026-09-30")
            .get(clientPath()).then().statusCode(200).extract().asString()
        val figures = mapper.readValue<FundPeriodFiguresDto>(body)
        val adapter = PensionReturnDataAdapter(FixedSources(figures), NoCorporateFacts, NoCompanyBooks)
        val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
        listOf("PSP10-12-FUND", "PSP20-12-FUND", "PSP30-12", "PSP34-12-FUND", "PEF13-04").forEach { code ->
            val definition = catalogue.definition(code)!!
            val values = runBlocking {
                adapter.fetch(
                    catalogue,
                    definition,
                    FUND_ID,
                    ReportingPeriod(Periodicity.MONTH, LocalDate.parse("2026-09-30")),
                )
            }
            assertThat(values.keys).containsExactlyInAnyOrderElementsOf(definition.datapoints)
        }
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl())
            .queryParam("periodStart", "2026-09-01").queryParam("periodEnd", "2026-09-30")
            .get(clientPath()).then().statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "sealedClosePact")
    fun `selected sealed close exposes raw JSON scope and immutable receipt headers`(mockServer: MockServer) {
        assertThat(sealedClientPath()).isEqualTo(SEALED_PATH)
        val response = given().baseUri(
            mockServer.getUrl(),
        ).get(sealedClientPath()).then().statusCode(200).extract().response()
        assertThat(response.header("X-Reporting-Close-Id")).isEqualTo(SEALED_CLOSE_ID)
        assertThat(response.header("X-Reporting-Close-Revision")).matches("[1-9][0-9]*")
        assertThat(response.header("X-Reporting-Payload-Schema-Version")).isEqualTo("1.3.0")
        assertThat(response.header("X-Reporting-Payload-SHA256")).matches("[0-9a-f]{64}")
        val payload = mapper.readTree(response.asByteArray())
        val figures = mapper.treeToValue(payload, FundPeriodFiguresDto::class.java)
        assertThat(payload.path("fundId").asText()).isEqualTo(SEALED_FUND_ID)
        assertThat(payload.path("periodEnd").asText()).isEqualTo("2014-03-31")
        assertThat(payload.path("currency").asText()).isEqualTo("CZK")
        val adapter = PensionReturnDataAdapter(
            FixedSources(
                figures,
                VerifiedFundClose(
                    UUID.fromString(SEALED_CLOSE_ID),
                    1,
                    "1.3.0",
                    response.header("X-Reporting-Payload-SHA256"),
                    figures,
                    payload,
                ),
            ),
            NoCorporateFacts,
            NoCompanyBooks,
        )
        val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
        listOf("PEF12-04-FUND", "PEF14-04").forEach { code ->
            val definition = catalogue.definition(code)!!
            val extraction = runBlocking {
                adapter.fetchClosedFund(
                    catalogue,
                    definition,
                    UUID.fromString(SEALED_FUND_ID),
                    ReportingPeriod(Periodicity.QUARTER, LocalDate.parse("2014-03-31")),
                    UUID.fromString(SEALED_CLOSE_ID),
                )
            }
            assertThat(extraction.values.keys).containsExactlyInAnyOrderElementsOf(definition.datapoints)
            assertThat(
                extraction.fundCloseLineage.cellPaths.keys,
            ).containsExactlyInAnyOrderElementsOf(definition.datapoints)
        }
    }

    @Test
    @PactTestFor(pactMethod = "sealedCloseNoAuthPact")
    fun `selected sealed close without identity is refused`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(sealedClientPath()).then().statusCode(401)
    }

    private class FixedSources(
        private val figures: FundPeriodFiguresDto,
        private val closed: VerifiedFundClose? = null,
    ) : PensionReportingSources {
        override suspend fun fund(fundId: UUID, from: LocalDate, to: LocalDate) = figures

        override suspend fun closedFund(fundId: UUID, closeId: UUID): VerifiedFundClose = checkNotNull(closed)

        override suspend fun participants(companyId: UUID, from: LocalDate, to: LocalDate): ParticipantAggregatesDto =
            error("not used by fund returns")
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-pension-fund-service"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** Must match PensionFundPactFolderProviderVerificationTest (openbank-pension-fund-service). */
        const val STATE = "a pension fund with a published September 2026 NAV exists"
        const val FUND_ID = "f0a7c1e2-0000-4000-8000-000000012425"
        const val SEALED_STATE = "a sealed pension fund reporting close exists for March 2014"
        const val SEALED_FUND_ID = "f0a7c1e2-0000-4000-8000-000000012426"
        const val SEALED_CLOSE_ID = "f0a7c1e2-0000-4000-8000-0000000c2014"

        /** LITERAL, retyped from pension-fund-service's FundReportingResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/reporting/funds/$FUND_ID/period-figures"
        const val SEALED_PATH = "/api/v1/reporting/funds/$SEALED_FUND_ID/period-closes/$SEALED_CLOSE_ID"

        fun clientPath(): String {
            val base = PensionFundReportingClient::class.java.getAnnotation(Path::class.java).value
            val sub = PensionFundReportingClient::class.java.methods.single { it.name == "periodFigures" }
                .getAnnotation(Path::class.java).value
            return (base + sub).replace("{fundId}", FUND_ID)
        }

        fun sealedClientPath(): String {
            val base = PensionFundReportingClient::class.java.getAnnotation(Path::class.java).value
            val sub = PensionFundReportingClient::class.java.methods.single { it.name == "sealedPeriod" }
                .getAnnotation(Path::class.java).value
            return (base + sub).replace("{fundId}", SEALED_FUND_ID).replace("{closeId}", SEALED_CLOSE_ID)
        }
    }
}
