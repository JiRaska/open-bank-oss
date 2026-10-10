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
import com.openbank.tax.infrastructure.returns.pension.PensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import com.openbank.tax.infrastructure.returns.pension.TreasuryCompanyPortfolio
import com.openbank.tax.infrastructure.returns.pension.TreasuryPortfolioClient
import com.openbank.tax.infrastructure.returns.pension.TreasuryPortfolioDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer contract: tax-reporting-service -> the pension company's treasury-service instance
 * `GET /api/v1/treasury/portfolio/period-end?date=` (ČNB PSP 34-12 PS, ADR-0337). Replayed by
 * treasury-service's @PactFolder provider verification and its negative-auth twin (branch
 * feat/treasury-pension-co-portfolio). Literal expected path, reflected request path; the decoded
 * body goes through the real PSP 34-12 PS mapping and catalogue rules.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingTreasuryPortfolioPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingTreasuryPortfolioPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun portfolioPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the pension company's portfolio at the 2026 year end")
        .path(EXPECTED_PATH)
        .query("date=2026-12-31")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("asOf", "2026-12-31")
                o.stringType("currency", "CZK")
                o.minArrayLike("positions", 1) { p ->
                    p.stringType("instrumentClass", "GOVERNMENT_BOND")
                    p.stringType("isin", "CZ0001005037")
                    p.stringMatcher("quantity", DECIMAL, "1000")
                    p.stringMatcher("valuation", DECIMAL, "1012345.67")
                    p.stringType("valuationCurrency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the pension company's portfolio with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .query("date=2026-12-31")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "portfolioPact")
    fun `the portfolio decodes into the adapter DTO and assembles PSP 34-12 PS`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(mockServer.getUrl()).queryParam("date", "2026-12-31")
            .get(clientPath()).then().statusCode(200).extract().asString()
        val portfolio = TreasuryCompanyPortfolio.decode(mapper.readValue<TreasuryPortfolioDto>(body))
        val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
        val definition = catalogue.definition("PSP34-12-PS")!!
        val values = runBlocking {
            PensionReturnDataAdapter(NoSources, NoCorporateFacts, NoCompanyBooks, { portfolio }, "CZK")
                .fetch(
                    catalogue,
                    definition,
                    "company",
                    ReportingPeriod(Periodicity.QUARTER, LocalDate.parse("2026-12-31")),
                )
        }
        assertThat(values.keys).containsExactlyInAnyOrderElementsOf(definition.datapoints)
        assertThat(values.getValue("government_bond_valuation")).isEqualByComparingTo("1012345.67")
        assertThat(definition.validate(values)).isEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).queryParam("date", "2026-12-31")
            .get(clientPath()).then().statusCode(401)
    }

    private object NoSources : PensionReportingSources {
        override suspend fun fund(fundId: UUID, from: LocalDate, to: LocalDate): FundPeriodFiguresDto =
            error("not used by PSP 34-12 PS")

        override suspend fun participants(from: LocalDate, to: LocalDate): ParticipantAggregatesDto =
            error("not used by PSP 34-12 PS")
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-treasury-service"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** Must match the treasury-service @PactFolder provider state (feat/treasury-pension-co-portfolio). */
        const val STATE = "the pension company holds investment positions at 2026-12-31"

        /** LITERAL, retyped from the shared portfolio contract — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/treasury/portfolio/period-end"

        private const val DECIMAL = "-?[0-9]+(\\.[0-9]+)?"

        fun clientPath(): String {
            val base = TreasuryPortfolioClient::class.java.getAnnotation(Path::class.java).value
            val sub = TreasuryPortfolioClient::class.java.methods.single { it.name == "periodEnd" }
                .getAnnotation(Path::class.java).value
            return base + sub
        }
    }
}
