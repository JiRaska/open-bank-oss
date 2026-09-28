// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.LoanInstrumentMapper
import com.openbank.risk.infrastructure.client.LendingAdapter
import com.openbank.risk.infrastructure.client.LoanBookResponse
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for lending's loan-book read, `GET /api/v1/lending/loan-book`
 * (ADR-0314 D4). The pact is committed to `pacts/` (git-pact, ADR-0063) and REPLAYED by
 * `LendingLoanBookPactProviderVerificationTest` (`@PactFolder`) in openbank-lending-service on every PR.
 *
 * The expected path is a LITERAL, never derived from [com.openbank.risk.infrastructure.client.LendingRestClient]'s
 * `@Path`: deriving both sides from the annotation keeps the test green when the client points at a
 * route that does not exist. Only the provider replay can catch a wrong path, which is why the
 * replay is not optional (CLAUDE.md, Contract tests).
 *
 * The body is parsed with the client's own DTO ([LoanBookResponse]) and taken through
 * [LendingAdapter.toContract] and [LoanInstrumentMapper] — so a renamed or retyped field reddens
 * this test, not a snapshot in production.
 *
 * The as-of is deliberately in 2020: lending's book as of a date contains only loans disbursed by
 * then, so the provider's seeded loan is the WHOLE book at that date even when other tests in the
 * same provider JVM have booked loans today — every array element must match the template.
 *
 * Regenerate on change: `./gradlew :openbank-risk-engine:test --tests "*LendingLoanBookPactConsumerTest*"`
 * and commit `pacts/openbank-risk-engine-openbank-lending-service.json` in the same PR.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-lending-service", pactVersion = PactSpecVersion.V3)
class LendingLoanBookPactConsumerTest {

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @Pact(consumer = "openbank-risk-engine", provider = "openbank-lending-service")
    fun loanBookPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the loan book holds one active FIXED CZK loan with two remaining installments")
        .uponReceiving("GET the loan book as of a date")
        .path("/api/v1/lending/loan-book")
        .query("asOf=2020-06-30")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue: the book must be the one ASKED for — the engine refuses another day's.
                o.stringValue("asOf", "2020-06-30")
                o.minArrayLike("loans", 1, 1) { l ->
                    l.uuid("loanId", UUID.fromString("0190a4c0-0000-7000-8000-00000000000a"))
                    l.uuid("counterpartyRef", UUID.fromString("0190a4c0-0000-7000-8000-000000000001"))
                    l.stringType("status", "ACTIVE")
                    l.stringType("currency", "CZK")
                    l.stringType("glAccountCode", "1200")
                    l.decimalType("outstandingPrincipal", 2000.00)
                    l.decimalType("nominalAnnualRate", 0.06)
                    l.`object`("rateTerms") { r ->
                        r.stringValue("rateType", "FIXED")
                        r.nullValue("rateIndex")
                        r.nullValue("spread")
                        r.nullValue("resetFrequencyMonths")
                        r.nullValue("nextResetDate")
                    }
                    l.stringType("method", "ANNUITY")
                    l.integerType("periodsPerYear", 12)
                    l.date("disbursedOn", "yyyy-MM-dd")
                    l.date("maturityDate", "yyyy-MM-dd")
                    l.nullValue("ifrs9Stage")
                    l.minArrayLike("remainingInstallments", 1, 1) { i ->
                        i.integerType("number", 2)
                        i.date("dueDate", "yyyy-MM-dd")
                        i.decimalType("principal", 1000.00)
                        i.decimalType("interest", 10.00)
                    }
                }
            }.build(),
        )
        .toPact()

    /**
     * The negative case (ADR-0279 #3): without a bearer token lending must answer 401. A contract
     * that only covers success stays green when the provider stops enforcing authz — and this read
     * returns the whole loan book.
     */
    @Pact(consumer = "openbank-risk-engine", provider = "openbank-lending-service")
    fun loanBookUnauthorizedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_IDENTITY)
        .uponReceiving("GET the loan book with no identity is 401")
        .path("/api/v1/lending/loan-book")
        .query("asOf=2020-06-30")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "loanBookUnauthorizedPact")
    fun `the loan book is refused 401 to a caller with no identity`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).queryParam("asOf", "2020-06-30").get("/api/v1/lending/loan-book")
            .then().statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "loanBookPact")
    fun `the engine parses lending's loan book into loan instruments`(mockServer: MockServer) {
        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .queryParam("asOf", "2020-06-30")
            .get("/api/v1/lending/loan-book")
            .then()
            .statusCode(200)
            .extract().asString()

        val book = mapper.readValue(raw, LoanBookResponse::class.java)
        assertThat(book.asOf).isEqualTo(LocalDate.parse("2020-06-30"))
        val contract = LendingAdapter.toContract(book.loans.single())
        assertThat(contract.glAccountCode).isEqualTo("1200")
        assertThat(contract.rateType).isEqualTo("FIXED")
        assertThat(contract.remainingInstallments).isNotEmpty()
        // The mock's example sums consistently only by accident of its values; the kind and
        // parse are what this asserts — the outstanding/schedule rule is LoanInstrumentTest's.
        val instrument = LoanInstrumentMapper.toInstrument(
            contract.copy(outstandingPrincipal = contract.remainingInstallments.sumOf { it.principal }),
        )
        assertThat(instrument.kind).isEqualTo(InstrumentKind.AMORTISING_LOAN)
    }

    private companion object {
        /** Must equal lending's `LoanBookPactState.NO_IDENTITY` — the provider keys on it. */
        const val NO_IDENTITY = "no valid identity is presented"
    }
}
