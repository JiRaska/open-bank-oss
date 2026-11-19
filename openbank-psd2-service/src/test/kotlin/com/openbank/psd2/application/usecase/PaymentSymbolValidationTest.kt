// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.usecase

import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.out.ConsentServiceClient
import com.openbank.psd2.application.port.out.TransactionServiceClient
import com.openbank.psd2.domain.model.DomesticCzPayment
import com.openbank.psd2.domain.model.ObAccountRef
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.SipoPayment
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/** VS/SS/KS are 1-10 ASCII digits (libs-domain CzechPaymentSymbol); anything else is a 400 before consent. */
class PaymentSymbolValidationTest {
    private val consentClient = mockk<ConsentServiceClient>()
    private val transactionClient = mockk<TransactionServiceClient>()
    private val service = PaymentInitiationService(transactionClient, consentClient)

    @ParameterizedTest
    @ValueSource(strings = ["12345678901", "12A4", "-1", "12 34", "١٢٣"])
    fun `DOMESTIC_CZ refuses a malformed symbol in any of the three fields before consent`(bad: String) {
        listOf(
            domestic(vs = bad),
            domestic(ss = bad),
            domestic(ks = bad),
        ).forEach { payment ->
            assertThatThrownBy { run(PaymentProduct.DOMESTIC_CZ, payment) }
                .isInstanceOf(Psd2RequestFormatException::class.java)
                .hasMessageContaining("Symbol")
        }
        coVerify(exactly = 0) { consentClient.validateConsent(any(), any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["12345678901", "VS1"])
    fun `SIPO refuses a malformed variable symbol before consent`(bad: String) {
        val payment = SipoPayment(account(), "1234567890", bad, null)
        assertThatThrownBy { run(PaymentProduct.SIPO, payment) }.isInstanceOf(Psd2RequestFormatException::class.java)
        coVerify(exactly = 0) { consentClient.validateConsent(any(), any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0308", "1234567890"])
    fun `one to ten digits pass for VS and SS`(good: String) {
        // Boundary: reaches consent (mock unstubbed throws MockKException), i.e. past the symbol check.
        val thrown = runCatching { run(PaymentProduct.DOMESTIC_CZ, domestic(vs = good, ss = good, ks = "0308")) }
            .exceptionOrNull()
        assertThat(thrown).isNotInstanceOf(Psd2RequestFormatException::class.java)
    }

    private fun run(product: PaymentProduct, payment: Any): PaymentInitiationResponse = runBlocking {
        service.initiatePayment(InitiatePaymentCommand("tpp-1", "consent-1", product, payment, "idem-1"))
    }

    private fun domestic(vs: String? = null, ss: String? = null, ks: String? = null) = DomesticCzPayment(
        endToEndIdentification = null,
        debtorAccount = account(),
        instructedAmount = ObAmount("CZK", BigDecimal("10.00")),
        creditorAccount = account(),
        creditorName = "Acme CZ",
        variableSymbol = vs,
        specificSymbol = ss,
        constantSymbol = ks,
        remittanceInformationUnstructured = null,
        requestedExecutionDate = null,
    )

    // Positional on purpose: the account reference has card-named optional fields that are irrelevant here.
    private fun account() = ObAccountRef("CZ6508000000192000145399", null, null, null, null, "CZK")

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `a blank symbol is treated as absent, as it was before this check`(blank: String) {
        val thrown = runCatching { run(PaymentProduct.DOMESTIC_CZ, domestic(vs = blank, ss = blank, ks = blank)) }
            .exceptionOrNull()
        assertThat(thrown).isNotInstanceOf(Psd2RequestFormatException::class.java)
    }

    @org.junit.jupiter.api.Test
    fun `the constant symbol stops at four digits, as the domestic-payment column does`() {
        val four = runCatching { run(PaymentProduct.DOMESTIC_CZ, domestic(ks = "9999")) }.exceptionOrNull()
        assertThat(four).isNotInstanceOf(Psd2RequestFormatException::class.java)
        assertThatThrownBy { run(PaymentProduct.DOMESTIC_CZ, domestic(ks = "12345")) }
            .isInstanceOf(Psd2RequestFormatException::class.java)
            .hasMessageContaining("constantSymbol must be 1-4")
    }

    @org.junit.jupiter.api.Test
    fun `the value that passed validation is the value forwarded - padded symbols are trimmed, blanks dropped`() {
        val forwarded = withNormalisedPaymentSymbols(domestic(vs = " 123 ", ss = "", ks = "0308 ")) as DomesticCzPayment
        assertThat(forwarded.variableSymbol).isEqualTo("123")
        assertThat(forwarded.specificSymbol).isNull()
        assertThat(forwarded.constantSymbol).isEqualTo("0308")
    }
}
