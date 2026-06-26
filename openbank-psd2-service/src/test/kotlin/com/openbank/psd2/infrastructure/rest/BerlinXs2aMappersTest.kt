// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.ObAccess
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentResponse
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Berlin Group XS2A 1.3.12 wire-shape conformance for the P1 mappers (ADR-0090): the three
 * details our bespoke `/open-banking/v2` got wrong — `_links` with href objects, amount as a
 * string, and lowerCamel `consentStatus`.
 */
class BerlinXs2aMappersTest {

    @Test
    fun `consentStatus maps to Berlin lowerCamel strings`() {
        val expected = mapOf(
            ConsentStatusOb.VALID to "valid",
            ConsentStatusOb.RECEIVED to "received",
            ConsentStatusOb.REVOKED_BY_PSU to "revokedByPsu",
            ConsentStatusOb.TERMINATED_BY_ASPSP to "terminatedByAspsp",
            ConsentStatusOb.PARTIALLY_AUTHORISED to "partiallyAuthorised",
        )
        expected.forEach { (status, wire) ->
            assertThat(BerlinXs2aMappers.consentStatus(status)).isEqualTo(wire)
        }
    }

    @Test
    fun `consentCreated emits consentId, status string and href-wrapped _links`() {
        val body = BerlinXs2aMappers.consentCreated(sampleConsent())

        assertThat(body["consentId"]).isEqualTo("c-123")
        assertThat(body["consentStatus"]).isEqualTo("received")

        @Suppress("UNCHECKED_CAST")
        val links = body["_links"] as Map<String, Any>
        assertThat(links).containsKeys("self", "status", "scaStatus", "scaRedirect")
        @Suppress("UNCHECKED_CAST")
        val self = links["self"] as Map<String, String>
        assertThat(self["href"]).isEqualTo("/v1/consents/c-123")
        @Suppress("UNCHECKED_CAST")
        val sca = links["scaRedirect"] as Map<String, String>
        assertThat(sca["href"]).isEqualTo("https://sca.example/redirect")
    }

    @Test
    fun `balances render amount as a string, not a JSON number`() {
        val out = BerlinXs2aMappers.balances(
            "CZ6508000000192000145399",
            listOf(
                ObBalance(
                    balanceAmount = ObAmount("CZK", BigDecimal("1234.50")),
                    balanceType = "closingBooked",
                    lastChangeDateTime = null,
                    referenceDate = LocalDate.parse("2026-06-15"),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val balances = out["balances"] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val amount = balances[0]["balanceAmount"] as Map<String, Any?>
        assertThat(amount["amount"]).isInstanceOf(String::class.java)
        assertThat(amount["amount"]).isEqualTo("1234.50")
        assertThat(amount["currency"]).isEqualTo("CZK")
        assertThat(balances[0]["balanceType"]).isEqualTo("closingBooked")
    }

    @Test
    fun `accountList links each account to its balances and transactions under v1`() {
        val out = BerlinXs2aMappers.accountList(
            listOf(
                ObAccount(
                    resourceId = "acc-1",
                    iban = "CZ6508000000192000145399",
                    currency = "CZK",
                    ownerName = "Jan Novák",
                    name = "Běžný účet",
                    product = "current",
                    cashAccountType = "CACC",
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val accounts = out["accounts"] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val links = accounts[0]["_links"] as Map<String, Map<String, String>>
        assertThat(links["balances"]?.get("href")).isEqualTo("/v1/accounts/acc-1/balances")
        assertThat(links["transactions"]?.get("href")).isEqualTo("/v1/accounts/acc-1/transactions")
    }

    @Test
    fun `productSegment and productOf round-trip Berlin path segments`() {
        assertThat(BerlinXs2aMappers.productSegment(PaymentProduct.SEPA_CREDIT_TRANSFERS))
            .isEqualTo("sepa-credit-transfers")
        assertThat(BerlinXs2aMappers.productOf("instant-sepa-credit-transfers"))
            .isEqualTo(PaymentProduct.INSTANT_SEPA_CREDIT_TRANSFERS)
        assertThat(BerlinXs2aMappers.productOf("not-a-product")).isNull()
    }

    @Test
    fun `Czech COBS payment products map to Berlin path segments (P3)`() {
        assertThat(BerlinXs2aMappers.productSegment(PaymentProduct.DOMESTIC_CZ)).isEqualTo("domestic-cz")
        assertThat(BerlinXs2aMappers.productSegment(PaymentProduct.SIPO)).isEqualTo("sipo")
        assertThat(BerlinXs2aMappers.productOf("domestic-cz")).isEqualTo(PaymentProduct.DOMESTIC_CZ)
        assertThat(BerlinXs2aMappers.productOf("sipo")).isEqualTo(PaymentProduct.SIPO)
    }

    @Test
    fun `paymentInitiated emits ISO status, paymentId and href-wrapped _links incl scaRedirect`() {
        val resp = PaymentInitiationResponse(
            paymentId = "pay-9",
            transactionStatus = PaymentStatus.RCVD,
            scaStatus = "received",
            links = ObLinks(self = "/v1/payments/sepa-credit-transfers/pay-9"),
        )
        val body = BerlinXs2aMappers.paymentInitiated(PaymentProduct.SEPA_CREDIT_TRANSFERS, resp)

        assertThat(body["transactionStatus"]).isEqualTo("RCVD")
        assertThat(body["paymentId"]).isEqualTo("pay-9")

        @Suppress("UNCHECKED_CAST")
        val links = body["_links"] as Map<String, Map<String, String>>
        assertThat(links["self"]?.get("href")).isEqualTo("/v1/payments/sepa-credit-transfers/pay-9")
        assertThat(links["status"]?.get("href")).isEqualTo("/v1/payments/sepa-credit-transfers/pay-9/status")
        assertThat(links["scaRedirect"]?.get("href"))
            .isEqualTo("/v1/payments/sepa-credit-transfers/pay-9/authorisations")
    }

    @Test
    fun `paymentStatus renders the ISO 20022 transactionStatus code`() {
        assertThat(BerlinXs2aMappers.paymentStatus(PaymentStatus.ACSC)).isEqualTo(mapOf("transactionStatus" to "ACSC"))
    }

    private fun sampleConsent() = ObConsentResponse(
        consentId = "c-123",
        consentStatus = ConsentStatusOb.RECEIVED,
        access = ObAccess(accounts = null, balances = null, transactions = null, additionalInformation = null),
        recurringIndicator = true,
        validUntil = LocalDate.parse("2026-09-15"),
        frequencyPerDay = 4,
        lastActionDate = null,
        links = ObLinks(self = "/v1/consents/c-123", scaRedirect = "https://sca.example/redirect"),
    )
}
