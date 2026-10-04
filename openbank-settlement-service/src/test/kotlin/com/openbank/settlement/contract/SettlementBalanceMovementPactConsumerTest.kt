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
import com.openbank.settlement.infrastructure.client.BalanceResponse
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
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

/**
 * Consumer-driven contract for the **balance legs of a settlement**: `BalanceRestClient` posting the
 * payer debit and the payee credit to balance-service (issue #8345 — a money-path call that had
 * no contract). The request is serialised from the real [MoneyMovementRequest], so a renamed
 * field on the mirror reddens here; the response binds into [BalanceResponse], whose two fields
 * are non-null with no defaults.
 *
 * Both legs reuse the account balance-service already seeds for account-service's read contract
 * (`a CZK balance exists for the balance account`, 5000.00 booked). A replayed `referenceId` is a
 * no-op on the provider, so the interactions stay green across repeated runs of the seeded state.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class SettlementBalanceMovementPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    private fun movement(leg: String) = MoneyMovementRequest(
        amount = BigDecimal("250.00"),
        currency = "CZK",
        // The adapter's own reference shape: settlement-<leg>-<settlementId>.
        referenceId = "settlement-$leg-$SETTLEMENT_ID",
        description = "Settlement $SETTLEMENT_ID $leg leg",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun debitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST the payer debit leg of a settlement")
        .path("$EXPECTED_BALANCES_PATH/$PACT_ACCOUNT_ID/debit")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(movement("debit")))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("accountId", PACT_ACCOUNT_ID)
                o.stringValue("currency", "CZK")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun creditPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST the payee credit leg of a settlement")
        .path("$EXPECTED_BALANCES_PATH/$PACT_ACCOUNT_ID/credit")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(movement("credit")))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("accountId", PACT_ACCOUNT_ID)
                o.stringValue("currency", "CZK")
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `BalanceNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun debitUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a settlement debit leg with no M2M identity is refused")
        .path("$EXPECTED_BALANCES_PATH/$PACT_ACCOUNT_ID/debit")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(movement("debit")))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "debitUnauthenticatedPact")
    fun `a debit with no identity is refused with 401, never applied`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(movement("debit")))
            .post(clientPath("debit"))
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "debitPact")
    fun `the debit leg is accepted and binds into BalanceResponse`(mockServer: MockServer) {
        assertThat(clientPath("debit"))
            .describedAs("BalanceRestClient's @Path no longer produces the path this pact pins")
            .isEqualTo("$EXPECTED_BALANCES_PATH/$PACT_ACCOUNT_ID/debit")
        val response = post(mockServer, "debit")
        assertThat(response.accountId).isEqualTo(UUID.fromString(PACT_ACCOUNT_ID))
        assertThat(response.currency).isEqualTo("CZK")
    }

    @Test
    @PactTestFor(pactMethod = "creditPact")
    fun `the credit leg is accepted and binds into BalanceResponse`(mockServer: MockServer) {
        assertThat(clientPath("credit")).isEqualTo("$EXPECTED_BALANCES_PATH/$PACT_ACCOUNT_ID/credit")
        assertThat(post(mockServer, "credit").accountId).isEqualTo(UUID.fromString(PACT_ACCOUNT_ID))
    }

    // The cover reservation (POST .../holds) lives in this class because pact-jvm
    // runs with pact.writer.overwrite=true, so a second consumer class for the same
    // settlement -> balance pair would replace this file instead of merging into it.
    private val id = UUID.fromString("55555555-5555-5555-5555-555555555520")
    private val payer = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1")
    private val payee = UUID.fromString("77777777-7777-7777-7777-777777777720")
    private val missingAccount = UUID.fromString("88888888-8888-4888-8888-888888888820")
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

    private fun post(mockServer: MockServer, leg: String): BalanceResponse {
        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(movement(leg)))
            .post(clientPath(leg))
            .then()
            .statusCode(200)
            .extract().asString()
        return mapper.readValue(raw, BalanceResponse::class.java)
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-settlement-service"
        const val PROVIDER = "openbank-balance-service"
        const val STATE = "a CZK balance exists for the balance account"

        /** balance-service's SINGLE_ACCOUNT_ID in BalancePactFolderProviderVerificationTest. */
        const val PACT_ACCOUNT_ID = "d5d5d5d5-d5d5-d5d5-d5d5-d5d5d5d5d5d5"
        const val SETTLEMENT_ID = "5e77e000-0000-4000-8000-000000000001"

        /** LITERAL, retyped from balance-service's `BalanceResource` — never derived from the client. */
        const val EXPECTED_BALANCES_PATH = "/api/v1/balances"

        fun clientPath(leg: String): String {
            val base = BalanceRestClient::class.java.getAnnotation(Path::class.java).value
            val method = BalanceRestClient::class.java.methods.single { it.name == leg }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{accountId}", PACT_ACCOUNT_ID)
        }
    }
}
