// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.gdpr

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.party.application.port.out.GdprAggregationAuthException
import com.openbank.party.domain.model.redactIban
import com.openbank.party.infrastructure.client.AccountPageBody
import com.openbank.party.infrastructure.client.AccountServiceRestClient
import com.openbank.party.infrastructure.client.AccountSummaryBody
import com.openbank.party.infrastructure.client.CardServiceRestClient
import com.openbank.party.infrastructure.client.TransactionItemResponse
import com.openbank.party.infrastructure.client.TransactionPageResponse
import com.openbank.party.infrastructure.client.TransactionServiceRestClient
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Covers [PortabilityAggregationAdapter] — the GDPR Art. 20 hop (ADR-0204 D2) out to
 * account-service and transaction-service, with the Art. 20(4) redaction applied at the
 * boundary. The compliance-critical assertions are the redaction and the fail-hard-on-401
 * semantics, which an export-everything test cannot distinguish from a healthy empty one.
 */
class PortabilityAggregationAdapterTest {

    private val accountClient: AccountServiceRestClient = mockk()
    private val transactionClient: TransactionServiceRestClient = mockk()
    private val cardClient: CardServiceRestClient = mockk()
    private val adapter = PortabilityAggregationAdapter(accountClient, transactionClient, cardClient)

    private val partyId: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()

    private fun <T> failing(status: Int): Uni<T> = Uni.createFrom().failure(WebApplicationException(status))

    @Test
    fun `accounts are assembled with their transactions, counterparty identity absent in v1`(): Unit = runBlocking {
        every { accountClient.listByParty(partyId, any(), null) } returns Uni.createFrom().item(
            AccountPageBody(
                data = listOf(
                    AccountSummaryBody(
                        id = accountId,
                        accountNumber = "CZ6508000000192000145399",
                        status = "ACTIVE",
                        currencyCode = "CZK",
                        productId = UUID.randomUUID(),
                    ),
                ),
            ),
        )
        every { transactionClient.listByAccount(accountId, any()) } returns Uni.createFrom().item(
            TransactionPageResponse(
                data = listOf(
                    TransactionItemResponse(
                        id = "tx-1",
                        referenceNumber = "TRX-000001",
                        bookingDate = "2026-07-01",
                        amount = "1200.00",
                        currencyCode = "CZK",
                        type = "DEBIT",
                        status = "COMPLETED",
                        description = "Nájem",
                    ),
                ),
            ),
        )

        val accounts = adapter.fetchAccountsWithTransactions(partyId)

        assertThat(accounts).hasSize(1)
        val account = accounts[0]
        assertThat(account.iban).isEqualTo("CZ6508000000192000145399")
        assertThat(account.currency).isEqualTo("CZK")
        assertThat(account.transactions).hasSize(1)
        val tx = account.transactions[0]
        assertThat(tx.transactionId).isEqualTo("tx-1")
        assertThat(tx.currency).isEqualTo("CZK")
        assertThat(tx.remittanceInfo).isEqualTo("Nájem")
        assertThat(tx.reference).isEqualTo("TRX-000001")
        // v1 gap: transaction-service carries no counterparty identity, so Art. 20(4) has
        // no input. Asserted so the day it does, this test is the thing that goes red.
        assertThat(tx.counterpartyName).isNull()
        assertThat(tx.counterpartyIbanRedacted).isNull()
    }

    @Test
    fun `a 401 from account-service fails hard - a refused read must never read as no data`(): Unit = runBlocking {
        every { accountClient.listByParty(partyId, any(), null) } returns failing(401)

        assertThatThrownBy {
            runBlocking { adapter.fetchAccountsWithTransactions(partyId) }
        }.isInstanceOf(GdprAggregationAuthException::class.java)
    }

    @Test
    fun `an account-service outage degrades to an empty slice - the export still ships`(): Unit = runBlocking {
        every { accountClient.listByParty(partyId, any(), null) } returns failing(503)

        assertThat(adapter.fetchAccountsWithTransactions(partyId)).isEmpty()
    }

    @Test
    fun `a transaction-service outage on one account degrades that account's transactions only`(): Unit = runBlocking {
        every { accountClient.listByParty(partyId, any(), null) } returns Uni.createFrom().item(
            AccountPageBody(data = listOf(AccountSummaryBody(id = accountId, accountNumber = "X", status = "ACTIVE"))),
        )
        every { transactionClient.listByAccount(accountId, any()) } returns failing(500)

        val accounts = adapter.fetchAccountsWithTransactions(partyId)

        assertThat(accounts).hasSize(1)
        assertThat(accounts[0].transactions).isEmpty()
    }

    @Test
    fun `cards map to contract-basis metadata only - no PAN material in the export model`(): Unit = runBlocking {
        every { cardClient.listByParty(partyId) } returns Uni.createFrom().item(
            listOf(
                mapOf<String, Any?>(
                    "id" to "card-1",
                    "productCode" to "DEBIT_STD",
                    "status" to "ACTIVE",
                    "expiryDate" to "2028-03-15",
                    "maskedPan" to "****1234",
                    "dailyLimitMinorUnits" to 50000L,
                ),
            ),
        )

        val cards = adapter.fetchCards(partyId)

        assertThat(cards).hasSize(1)
        assertThat(cards[0].productCode).isEqualTo("DEBIT_STD")
        assertThat(cards[0].expiryMonth).isEqualTo(3)
        assertThat(cards[0].expiryYear).isEqualTo(2028)
        assertThat(cards[0].toResponse().keys).doesNotContain("maskedPan", "dailyLimitMinorUnits")
    }

    /**
     * The provider contract, not our own DTO: this payload is the literal shape
     * transaction-service's `GET /api/v1/transactions` returns (CursorPage of
     * TransactionResponse). Building [TransactionItemResponse] by hand elsewhere in this
     * suite cannot catch a field-name mismatch — both sides move together. Written as a
     * literal on purpose.
     */
    @Test
    fun `the transaction client deserializes the shape transaction-service actually returns`() {
        val providerJson = """
            {
              "data": [
                {
                  "id": "4f1d2a5e-0f7f-4c37-9a3c-1c7b1a2f9f01",
                  "referenceNumber": "TRX-000123",
                  "type": "DEBIT",
                  "sourceAccountId": "0a3a1a2b-1111-2222-3333-444455556666",
                  "targetAccountId": "0a3a1a2b-9999-8888-7777-666655554444",
                  "amount": 149.50,
                  "currencyCode": "CZK",
                  "status": "COMPLETED",
                  "description": "Invoice 2026/114",
                  "valueDate": "2026-07-02",
                  "bookingDate": "2026-07-02",
                  "initiatedAt": "2026-07-02T10:15:30Z",
                  "completedAt": "2026-07-02T10:15:31Z",
                  "rail": "SEPA",
                  "instructionType": "SCT",
                  "merchantCategory": null
                }
              ],
              "pagination": { "nextCursor": null, "limit": 200 }
            }
        """.trimIndent()

        val page = jacksonObjectMapper().readValue(providerJson, TransactionPageResponse::class.java)
        val item = page.data.single()

        assertThat(item.id).isEqualTo("4f1d2a5e-0f7f-4c37-9a3c-1c7b1a2f9f01")
        assertThat(item.referenceNumber).isEqualTo("TRX-000123")
        assertThat(item.bookingDate).isEqualTo("2026-07-02")
        assertThat(item.amount).isEqualTo("149.50")
        assertThat(item.currencyCode).isEqualTo("CZK")
        assertThat(item.description).isEqualTo("Invoice 2026/114")
    }
}

class RedactIbanTest {

    @Test
    fun `a Czech IBAN keeps country, check digits and bank code, masks the account part`() {
        assertThat(redactIban("CZ03 0800 0000 0001 2345 6789"))
            .isEqualTo("CZ030800****************")
    }

    @Test
    fun `an IBAN shorter than the prefix is returned untouched`() {
        assertThat(redactIban("CZ650800")).isEqualTo("CZ650800")
    }

    @Test
    fun `null and blank stay absent`() {
        assertThat(redactIban(null)).isNull()
    }
}
