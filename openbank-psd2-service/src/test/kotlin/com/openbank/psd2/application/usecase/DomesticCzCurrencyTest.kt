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
import com.openbank.psd2.domain.model.PaymentProduct
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DomesticCzCurrencyTest {
    private val consentClient = mockk<ConsentServiceClient>()
    private val transactionClient = mockk<TransactionServiceClient>()
    private val paymentInitiationService = PaymentInitiationService(transactionClient, consentClient)

    @Test
    fun `DOMESTIC_CZ rejects EUR before asking consent or initiating a payment`(): Unit = runBlocking {
        val payment = DomesticCzPayment(
            endToEndIdentification = null,
            debtorAccount = account("CZ6508000000192000145399"),
            instructedAmount = ObAmount("EUR", BigDecimal("10.00")),
            creditorAccount = account("CZ1234567890123456789012"),
            creditorName = "Acme CZ",
            variableSymbol = null,
            specificSymbol = null,
            constantSymbol = null,
            remittanceInformationUnstructured = null,
            requestedExecutionDate = null,
        )

        assertThatThrownBy {
            runBlocking {
                paymentInitiationService.initiatePayment(
                    InitiatePaymentCommand("tpp-1", "consent-1", PaymentProduct.DOMESTIC_CZ, payment, "idem-eur"),
                )
            }
        }.isInstanceOf(Psd2RequestFormatException::class.java).hasMessageContaining("requires CZK")
        coVerify(exactly = 0) { consentClient.validateConsent(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            transactionClient.initiatePayment(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    private fun account(iban: String) = ObAccountRef(
        iban = iban,
        bban = null,
        pan = null,
        maskedPan = null,
        msisdn = null,
        currency = "CZK",
    )
}
