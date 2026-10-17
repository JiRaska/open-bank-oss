// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.contract

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
import com.openbank.party.application.port.out.GdprAggregationAuthException
import com.openbank.party.infrastructure.client.AccountPageBody
import com.openbank.party.infrastructure.client.AccountServiceRestClient
import com.openbank.party.infrastructure.client.AccountSummaryBody
import com.openbank.party.infrastructure.client.CardServiceRestClient
import com.openbank.party.infrastructure.client.TransactionPageResponse
import com.openbank.party.infrastructure.client.TransactionServiceRestClient
import com.openbank.party.infrastructure.gdpr.PortabilityAggregationAdapter
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: party-service -> transaction-service `GET /api/v1/transactions?accountId=`,
 * the per-account leg of the GDPR Art. 20 portability export (ADR-0204 D2).
 *
 * What the contract pins: the page wrapper's rows arrive under `data` (a `CursorPage`, not
 * `items`); the row carries `id` / `referenceNumber` / `type` / `amount` / `currencyCode` /
 * `status` / `description` / `bookingDate` — the exact fields `TransactionItemResponse`
 * deserializes, because `@JsonIgnoreProperties(ignoreUnknown = true)` makes a renamed field a
 * SILENT null and the export would ship transactions with empty amounts rather than fail.
 *
 * Failure semantics are half the contract: the aggregation adapter degrades every downstream
 * outage to an empty slice, but 401/403 fails the export hard (`GdprAggregationAuthException`) —
 * a refused read must never read as "the subject has no transactions". The 401 leg replays that.
 *
 * Replayed by transaction-service's `TransactionPactFolderProviderVerificationTest` (git pacts,
 * runs on every PR per #2338) and the broker twin, which must carry the same `@State` handlers.
 *
 * The account and card clients are local fakes: this pact's integration point is the transaction
 * list only, and the fakes keep the published pact free of interactions the contract does not
 * cover.
 */
@ExtendWith(PactConsumerTestExt::class)
@TestMethodOrder(MethodOrderer.MethodName::class)
@PactTestFor(providerName = PartyTransactionListPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class PartyTransactionListPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listTransactionsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET transactions for the subject account during a portability export")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("accountId=$ACCOUNT_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("data") { arr ->
                    arr.`object` { tx ->
                        tx.stringValue("id", TRANSACTION_ID)
                        tx.stringValue("referenceNumber", REFERENCE_NUMBER)
                        tx.stringValue("type", "CREDIT")
                        tx.numberValue("amount", 100.0)
                        tx.stringValue("currencyCode", "CZK")
                        tx.stringValue("status", "COMPLETED")
                        tx.stringValue("bookingDate", BOOKING_DATE)
                        tx.stringValue("description", DESCRIPTION)
                    }
                }
                o.`object`("pagination") { p ->
                    p.booleanValue("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listTransactionsEmptyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_TRANSACTIONS_STATE)
        .uponReceiving("GET transactions for an account with no history")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("accountId=$EMPTY_ACCOUNT_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.array("data") { arr -> }
                o.`object`("pagination") { p ->
                    p.booleanValue("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun listTransactionsUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET transactions with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("accountId=$ACCOUNT_ID&limit=$PAGE_SIZE")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listTransactionsPact")
    fun `the export assembles the subject transaction history`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val result = runBlocking {
            adapterFor(mockServer).fetchAccountsWithTransactions(UUID.fromString(PARTY_ID))
        }
        assertThat(result).hasSize(1)
        val account = result.single()
        assertThat(account.iban).isEqualTo(IBAN)
        assertThat(account.transactions).hasSize(1)
        val tx = account.transactions.single()
        assertThat(tx.transactionId).isEqualTo(TRANSACTION_ID)
        assertThat(tx.reference).isEqualTo(REFERENCE_NUMBER)
        assertThat(tx.bookingDate).isEqualTo(BOOKING_DATE)
        assertThat(tx.amount).isEqualTo(AMOUNT_TEXT)
        assertThat(tx.currency).isEqualTo("CZK")
        assertThat(tx.type).isEqualTo("CREDIT")
        assertThat(tx.status).isEqualTo("COMPLETED")
        assertThat(tx.remittanceInfo).isEqualTo(DESCRIPTION)
    }

    @Test
    @PactTestFor(pactMethod = "listTransactionsEmptyPact")
    fun `an account with no history exports as an empty slice`(mockServer: MockServer) {
        val result = runBlocking {
            adapterFor(mockServer, EMPTY_ACCOUNT_ID).fetchAccountsWithTransactions(UUID.fromString(PARTY_ID))
        }
        assertThat(result).hasSize(1)
        assertThat(result.single().transactions).isEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "listTransactionsUnauthenticatedPact")
    fun `a refused transaction read fails the export hard`(mockServer: MockServer) {
        assertThatThrownBy {
            runBlocking { adapterFor(mockServer).fetchAccountsWithTransactions(UUID.fromString(PARTY_ID)) }
        }.isInstanceOf(GdprAggregationAuthException::class.java)
            .hasMessageContaining("HTTP 401")
    }

    /**
     * The real aggregation adapter: transaction client bound to the pact mock server; account
     * and card clients are local fakes so the published pact covers only this integration point.
     */
    private fun adapterFor(mockServer: MockServer, accountId: String = ACCOUNT_ID): PortabilityAggregationAdapter =
        PortabilityAggregationAdapter(fakeAccountClient(accountId), transactionClientFor(mockServer), fakeCardClient())

    private fun fakeAccountClient(accountId: String): AccountServiceRestClient = object : AccountServiceRestClient {
        override fun listByParty(partyId: UUID, limit: Int, cursor: String?): Uni<AccountPageBody> =
            Uni.createFrom().item(
                AccountPageBody(
                    data = listOf(
                        AccountSummaryBody(
                            id = UUID.fromString(accountId),
                            accountNumber = IBAN,
                            status = "ACTIVE",
                            currencyCode = "CZK",
                        ),
                    ),
                    pagination = null,
                ),
            )
    }

    private fun fakeCardClient(): CardServiceRestClient = object : CardServiceRestClient {
        override fun listByParty(partyId: UUID): Uni<List<Map<String, Any?>>> = Uni.createFrom().item(emptyList())
    }

    private fun transactionClientFor(mockServer: MockServer): TransactionServiceRestClient =
        object : TransactionServiceRestClient {
            override fun listByAccount(accountId: UUID, limit: Int): Uni<TransactionPageResponse> =
                Uni.createFrom().item {
                    val response = given().baseUri(mockServer.getUrl()).contentType("application/json")
                        .queryParam("accountId", accountId.toString())
                        .queryParam("limit", limit)
                        .get(EXPECTED_PATH)
                    if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
                    mapper.readValue<TransactionPageResponse>(response.asString())
                }
        }

    companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val CONSUMER = "openbank-party-service"
        const val PROVIDER = "openbank-transaction-service"

        /** Must match the seeded row in TransactionPactFolderProviderVerificationTest. */
        const val STATE = "a posted transaction exists for the pact account"
        const val NO_TRANSACTIONS_STATE = "no transactions exist for the requested account"

        const val TRANSACTION_ID = "88888888-9999-4aaa-bbbb-cccccccccccc"
        const val ACCOUNT_ID = "77777777-8888-4999-aaaa-bbbbbbbbbbbb"
        const val EMPTY_ACCOUNT_ID = "77777777-8888-4999-aaaa-bbbbbbbbbbbe"
        const val PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val IBAN = "CZ6508000000192000145399"
        const val REFERENCE_NUMBER = "TXN-PARTY-PACT-001"
        const val BOOKING_DATE = "2026-01-15"
        const val DESCRIPTION = "pact portability fixture"
        const val PAGE_SIZE = "200"

        /**
         * The pact mock answers the declared number 100.0, and Jackson's String coercion renders
         * it "100.0" into the client's `amount: String?` — the assertion pins what the export
         * actually carries, not the provider's BigDecimal scale.
         */
        const val AMOUNT_TEXT = "100.0"

        /** LITERAL, retyped from transaction-service's TransactionResource — never from the client. */
        const val EXPECTED_PATH = "/api/v1/transactions"

        fun clientPath(): String {
            val base = TransactionServiceRestClient::class.java.getAnnotation(Path::class.java).value
            return base
        }
    }
}
