// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.`in`.PaymentInitiationUseCase
import com.openbank.psd2.domain.model.ObAccountRef
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.PaymentInitiation
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * The bespoke `/open-banking/v2/payments` surface (money-path-adjacent): idempotency replay keyed
 * per-TPP/per-product, the mandatory `Idempotency-Key` guard, and the status lookup's unknown
 * product-segment handling.
 */
class PisResourceTest {

    private val pis = mockk<PaymentInitiationUseCase>()
    private val idempotencyStore = mockk<IdempotencyStore>()
    private val objectMapper = ObjectMapper()
    private val resource = PisResource(pis, idempotencyStore, objectMapper)

    private fun ctxWithTpp(tppId: String?): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>()
        every { ctx.getProperty("tppId") } returns tppId
        return ctx
    }

    private fun samplePayment() = PaymentInitiation(
        endToEndIdentification = "e2e-1",
        debtorAccount = ObAccountRef("CZ6508000000192000145399", null, null, null, null, "CZK"),
        instructedAmount = ObAmount("CZK", BigDecimal("10.00")),
        creditorAccount = ObAccountRef("CZ1234567890123456789012", null, null, null, null, "CZK"),
        creditorName = "Acme",
        creditorAddress = null,
        remittanceInformationUnstructured = null,
        requestedExecutionDate = null,
    )

    private fun sampleResponse(id: String) = PaymentInitiationResponse(
        paymentId = id,
        transactionStatus = PaymentStatus.RCVD,
        scaStatus = "received",
        links = ObLinks(self = "/open-banking/v2/payments/sepa-credit-transfers/$id"),
    )

    @Test
    fun `initiateSepa returns 401 CERTIFICATE_MISSING when tppId absent`(): Unit = runBlocking {
        val response = resource.initiateSepa(samplePayment(), "consent-1", "idem-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `initiateSepa requires a non-blank Idempotency-Key`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { resource.initiateSepa(samplePayment(), "consent-1", "", ctxWithTpp("tpp-1")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Idempotency-Key")
    }

    @Test
    fun `initiateSepa replays a cached response on idempotency hit`(): Unit = runBlocking {
        val cacheKey = "psd2:payment:tpp-1:SEPA_CREDIT_TRANSFERS:idem-1"
        val cached = IdempotencyRecord(cacheKey, 201, """{"paymentId":"p-1"}""", OffsetDateTime.now())
        coEvery { idempotencyStore.get(cacheKey) } returns cached

        val response = resource.initiateSepa(samplePayment(), "consent-1", "idem-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
        assertThat(response.headers.getFirst("X-Idempotency-Replayed")).isEqualTo("true")
        coVerify(exactly = 0) { pis.initiatePayment(any()) }
    }

    @Test
    fun `initiateSepa delegates to the use case, caches and returns 201`(): Unit = runBlocking {
        val cacheKey = "psd2:payment:tpp-1:SEPA_CREDIT_TRANSFERS:idem-2"
        coEvery { idempotencyStore.get(cacheKey) } returns null
        coEvery {
            pis.initiatePayment(
                InitiatePaymentCommand("tpp-1", "consent-1", PaymentProduct.SEPA_CREDIT_TRANSFERS, samplePayment(), "idem-2"),
            )
        } returns sampleResponse("p-1")
        coEvery { idempotencyStore.save(cacheKey, 201, any()) } returns Unit

        val response = resource.initiateSepa(samplePayment(), "consent-1", "idem-2", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
        coVerify(exactly = 1) { idempotencyStore.save(cacheKey, 201, any()) }
    }

    @Test
    fun `initiateInstantSepa uses the INSTANT_SEPA_CREDIT_TRANSFERS product`(): Unit = runBlocking {
        val cacheKey = "psd2:payment:tpp-1:INSTANT_SEPA_CREDIT_TRANSFERS:idem-3"
        coEvery { idempotencyStore.get(cacheKey) } returns null
        coEvery {
            pis.initiatePayment(
                InitiatePaymentCommand(
                    "tpp-1",
                    "consent-1",
                    PaymentProduct.INSTANT_SEPA_CREDIT_TRANSFERS,
                    samplePayment(),
                    "idem-3",
                ),
            )
        } returns sampleResponse("p-2")
        coEvery { idempotencyStore.save(cacheKey, 201, any()) } returns Unit

        val response = resource.initiateInstantSepa(samplePayment(), "consent-1", "idem-3", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
    }

    @Test
    fun `getStatus returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getStatus("sepa-credit-transfers", "p-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getStatus returns 404 for an unknown product segment`(): Unit = runBlocking {
        val response = resource.getStatus("not-a-product", "p-1", ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `getStatus delegates to the use case and wraps transactionStatus`(): Unit = runBlocking {
        coEvery {
            pis.getPaymentStatus(GetPaymentStatusQuery("p-1", "tpp-1", PaymentProduct.SEPA_CREDIT_TRANSFERS))
        } returns PaymentStatus.ACSC

        val response = resource.getStatus("sepa-credit-transfers", "p-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["transactionStatus"]).isEqualTo("ACSC")
    }
}
