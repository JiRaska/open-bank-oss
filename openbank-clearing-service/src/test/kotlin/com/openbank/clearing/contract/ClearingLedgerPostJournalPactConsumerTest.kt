// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clearing.application.port.out.NetSettlementPosting
import com.openbank.clearing.infrastructure.client.ClearingLedgerRestClient
import com.openbank.clearing.infrastructure.client.JournalResponse
import com.openbank.clearing.infrastructure.client.NetSettlementJournalFactory
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for the **net-settlement ledger leg** (ADR-0281): clearing-service
 * posting `POST /api/v1/journals` once a cleared batch settles, Dr cash-clearing / Cr scheme
 * settlement in the batch currency (issue #8345 — a money-path call that had no contract).
 *
 * The body is built by the REAL [NetSettlementJournalFactory], so the GL ids it pins are the ones
 * production posts to; ledger-service's provider replay verifies them against the chart its own
 * migrations seed (`the standard chart of accounts is seeded`), which is the only place a wrong id
 * can be caught before a settlement fails to book. The response binds into [JournalResponse],
 * whose three fields are non-null with no defaults: a renamed `id` or `status` fails to construct.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class ClearingLedgerPostJournalPactConsumerTest {

    private val posting = NetSettlementPosting(
        batchId = UUID.fromString("c1ea0000-0000-4000-8000-000000000001"),
        batchReference = "CZ-CERTIS-20260120-001",
        cycleId = "CERTIS-1",
        idempotencyKey = "net-settlement:c1ea0000-0000-4000-8000-000000000001",
        currency = "CZK",
        settlementAmount = BigDecimal("125000.00"),
        valueDate = LocalDate.of(2026, 1, 20),
    )

    private val requestBody: String = MAPPER.writeValueAsString(NetSettlementJournalFactory.build(posting))

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun postNetSettlementJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced two-line CZK net-settlement journal for a cleared batch")
        .path(EXPECTED_JOURNALS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.uuid("transactionId", posting.batchId)
                o.stringValue("status", "POSTED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "postNetSettlementJournalPact")
    fun `postJournal accepts the net-settlement leg and its answer binds into JournalResponse`(mockServer: MockServer) {
        assertThat(clientDerivedJournalsPath())
            .describedAs(
                "ClearingLedgerRestClient's @Path no longer produces the path this pact pins — fix the " +
                    "client or update EXPECTED_JOURNALS_PATH *and* re-verify against ledger-service",
            )
            .isEqualTo(EXPECTED_JOURNALS_PATH)
        // Pins the fixture as the case it claims to be: two legs, balanced, on the CZK GL pair.
        val request = NetSettlementJournalFactory.build(posting)
        assertThat(request.lines).hasSize(2)
        assertThat(request.lines.map { it.glAccountId }).containsExactly(
            NetSettlementJournalFactory.glPairFor("CZK").first,
            NetSettlementJournalFactory.glPairFor("CZK").second,
        )

        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post(clientDerivedJournalsPath())
            .then()
            .statusCode(201)
            .extract().asString()

        val response = MAPPER.readValue(raw, JournalResponse::class.java)
        assertThat(response.transactionId).isEqualTo(posting.batchId)
        assertThat(response.status).isEqualTo("POSTED")
    }

    private companion object {
        const val CONSUMER = "openbank-clearing-service"
        const val PROVIDER = "openbank-ledger-service"

        /** LITERAL, retyped from ledger-service's `JournalResource` — never derived from the client. */
        const val EXPECTED_JOURNALS_PATH = "/api/v1/journals"

        /** Same Jackson shape the REST client uses; plain BigDecimal keeps the amount scale on the wire. */
        val MAPPER = jacksonObjectMapper().enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)

        fun clientDerivedJournalsPath(): String =
            ClearingLedgerRestClient::class.java.getAnnotation(Path::class.java).value
    }
}
