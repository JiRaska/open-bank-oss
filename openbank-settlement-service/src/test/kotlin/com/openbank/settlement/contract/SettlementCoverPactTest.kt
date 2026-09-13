// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.adapter.SettlementCoverAdapter
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.SettlementCoverRequest
import com.openbank.settlement.infrastructure.client.SettlementCoverResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class SettlementCoverPactTest {
    private val id = UUID.fromString("55555555-5555-5555-5555-555555555520")
    private val payer = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1")
    private val payee = UUID.fromString("77777777-7777-7777-7777-777777777720")
    private val missingAccount = UUID.fromString("88888888-8888-4888-8888-888888888820")
    private val mapper = jacksonObjectMapper()
    private val amount = BigDecimal("125.50")

    @Pact(consumer = "openbank-settlement-service", provider = "openbank-balance-service")
    fun reserve(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the holds account with sufficient funds")
        .uponReceiving("settlement reserves payer cover until ledger projection consumes it")
        .path("/api/v1/balances/$payer/holds").method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(SettlementCoverRequest(amount, "CZK", "Settlement cover", id.toString())))
        .willRespondWith().status(201).headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.uuid("id")
                body.stringValue("accountId", payer.toString())
                body.numberValue("amount", amount)
                body.stringValue("currency", "CZK")
                body.stringValue("referenceId", id.toString())
                body.nullValue("expiresAt")
                body.nullValue("releasedAt")
            }.build(),
        ).toPact()

    @Pact(consumer = "openbank-settlement-service", provider = "openbank-balance-service")
    fun missingPayer(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no balance exists for the settlement cover account")
        .uponReceiving("settlement cannot reserve cover for a missing payer")
        .path("/api/v1/balances/$missingAccount/holds").method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(SettlementCoverRequest(amount, "CZK", "Settlement cover", id.toString())))
        .willRespondWith().status(404).headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { it.stringValue("error", "NOT_FOUND") }.build()).toPact()

    @Test
    @PactTestFor(pactMethod = "missingPayer")
    fun `a missing payer cannot be treated as reserved cover`(server: MockServer): Unit = runBlocking {
        val repository = mockk<SettlementRepository>()
        coEvery { repository.findById(id) } returns Settlement(
            id, missingAccount, payee, amount, "CZK", SettlementStatus.PENDING,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
            SettlementProtocol.LEDGER_PROJECTION,
        )
        val client = mockk<BalanceRestClient>()
        every { client.reserve(any(), any()) } answers { send(server, firstArg(), secondArg()) }
        val failure = runCatching { SettlementCoverAdapter(client, repository).reservePayer(id) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(WebApplicationException::class.java)
        assertThat((failure as WebApplicationException).response.status).isEqualTo(404)
    }

    @Test
    @PactTestFor(pactMethod = "reserve")
    fun `adapter consumes a full nonexpiring payer reservation`(server: MockServer): Unit = runBlocking {
        val repository = mockk<SettlementRepository>()
        coEvery { repository.findById(id) } returns Settlement(
            id, payer, payee, amount, "CZK", SettlementStatus.PENDING,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
            SettlementProtocol.LEDGER_PROJECTION,
        )
        val client = mockk<BalanceRestClient>()
        every { client.reserve(any(), any()) } answers { send(server, firstArg(), secondArg()) }
        SettlementCoverAdapter(client, repository).reservePayer(id)
    }

    private fun send(server: MockServer, account: UUID, body: SettlementCoverRequest): Uni<SettlementCoverResponse> {
        // Request uses the real adapter DTO and client route; provider replay validates the independent expectation.
        val type = BalanceRestClient::class.java
        val path = type.getAnnotation(Path::class.java).value +
            type.methods.single { it.name == "reserve" }.getAnnotation(Path::class.java).value
        val response = given().baseUri(server.getUrl()).contentType("application/json")
            .body(mapper.writeValueAsString(body)).post(path.replace("{accountId}", account.toString()))
        if (response.statusCode != 201) {
            return Uni.createFrom().failure(WebApplicationException(response.statusCode))
        }
        return Uni.createFrom().item(mapper.readValue(response.asString(), SettlementCoverResponse::class.java))
    }
}
