// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.PaymentInitiationUseCase
import com.openbank.psd2.domain.model.ObLinks
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
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Berlin Group XS2A `/v1/payments` surface (P2/P3 — pan-EU SEPA + Czech ČOBS products): product
 * resolution from the path segment, per-product JSON body deserialisation, mandatory
 * `Consent-ID`/`X-Request-ID`, idempotency replay and malformed-body handling.
 */
class BerlinPisResourceTest {

    private val pis = mockk<PaymentInitiationUseCase>()
    private val idempotencyStore = mockk<IdempotencyStore>()
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val resource = BerlinPisResource(pis, idempotencyStore, objectMapper)

    private fun ctxWithTpp(tppId: String?): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>()
        every { ctx.getProperty("tppId") } returns tppId
        return ctx
    }

    private fun sepaBody() = objectMapper.readTree(
        """
        {
          "endToEndIdentification": "e2e-1",
          "debtorAccount": {"iban": "CZ6508000000192000145399", "currency": "CZK"},
          "instructedAmount": {"currency": "CZK", "amount": 10.00},
          "creditorAccount": {"iban": "CZ1234567890123456789012", "currency": "CZK"},
          "creditorName": "Acme"
        }
        """.trimIndent(),
    )

    private fun sipoBody() = objectMapper.readTree(
        """
        {
          "debtorAccount": {"iban": "CZ6508000000192000145399", "currency": "CZK"},
          "sipoNumber": "1234567890",
          "variableSymbol": "999"
        }
        """.trimIndent(),
    )

    private fun sampleResponse(id: String) = PaymentInitiationResponse(
        paymentId = id,
        transactionStatus = PaymentStatus.RCVD,
        scaStatus = "received",
        links = ObLinks(self = "/v1/payments/sepa-credit-transfers/$id"),
    )

    @Test
    fun `initiate returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.initiate("sepa-credit-transfers", sepaBody(), "consent-1", "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `initiate returns 404 PRODUCT_UNKNOWN for an unsupported product segment`(): Unit = runBlocking {
        val response = resource.initiate("not-a-product", sepaBody(), "consent-1", "req-1", ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(404)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        assertThat(messages[0]["code"]).isEqualTo("PRODUCT_UNKNOWN")
    }

    @Test
    fun `initiate returns 401 CONSENT_INVALID when Consent-ID missing`(): Unit = runBlocking {
        val response = resource.initiate("sepa-credit-transfers", sepaBody(), null, "req-1", ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `initiate returns 400 FORMAT_ERROR when X-Request-ID missing`(): Unit = runBlocking {
        val response = resource.initiate("sepa-credit-transfers", sepaBody(), "consent-1", null, ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `initiate returns 400 FORMAT_ERROR when the body does not match the product`(): Unit = runBlocking {
        val malformed = objectMapper.readTree("""{"totally": "wrong shape", "instructedAmount": "not an object"}""")

        val response = resource.initiate(
            "sepa-credit-transfers",
            malformed,
            "consent-1",
            "req-1",
            ctxWithTpp("tpp-1"),
        )

        assertThat(response.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        assertThat(messages[0]["code"]).isEqualTo("FORMAT_ERROR")
    }

    @Test
    fun `initiate replays a cached response on idempotency hit`(): Unit = runBlocking {
        val cacheKey = "psd2:v1:payment:tpp-1:SEPA_CREDIT_TRANSFERS:req-1"
        val cached = IdempotencyRecord(cacheKey, 201, """{"paymentId":"p-1"}""", OffsetDateTime.now())
        coEvery { idempotencyStore.get(cacheKey) } returns cached

        val response = resource.initiate("sepa-credit-transfers", sepaBody(), "consent-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
        assertThat(response.headers.getFirst("X-Idempotency-Replayed")).isEqualTo("true")
        coVerify(exactly = 0) { pis.initiatePayment(any()) }
    }

    @Test
    fun `initiate deserialises the SEPA body, delegates and returns Berlin created body with Location`(): Unit =
        runBlocking {
            val cacheKey = "psd2:v1:payment:tpp-1:SEPA_CREDIT_TRANSFERS:req-2"
            coEvery { idempotencyStore.get(cacheKey) } returns null
            coEvery {
                pis.initiatePayment(match { it.tppId == "tpp-1" && it.product == PaymentProduct.SEPA_CREDIT_TRANSFERS })
            } returns sampleResponse("p-1")
            coEvery { idempotencyStore.save(cacheKey, 201, any()) } returns Unit

            val response = resource.initiate(
                "sepa-credit-transfers",
                sepaBody(),
                "consent-1",
                "req-2",
                ctxWithTpp("tpp-1"),
            )

            assertThat(response.status).isEqualTo(201)
            assertThat(response.headers.getFirst("Location")).isEqualTo("/v1/payments/sepa-credit-transfers/p-1")
            assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-2")
        }

    @Test
    fun `initiate deserialises the SIPO body into a SipoPayment`(): Unit = runBlocking {
        val cacheKey = "psd2:v1:payment:tpp-1:SIPO:req-3"
        coEvery { idempotencyStore.get(cacheKey) } returns null
        coEvery {
            pis.initiatePayment(
                match {
                    it.product == PaymentProduct.SIPO &&
                        (it.payment as com.openbank.psd2.domain.model.SipoPayment).sipoNumber == "1234567890"
                },
            )
        } returns sampleResponse("p-2")
        coEvery { idempotencyStore.save(cacheKey, 201, any()) } returns Unit

        val response = resource.initiate("sipo", sipoBody(), "consent-1", "req-3", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
    }

    @Test
    fun `getStatus returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getStatus("sepa-credit-transfers", "p-1", "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getStatus returns 404 for an unsupported product`(): Unit = runBlocking {
        val response = resource.getStatus("not-a-product", "p-1", "req-1", ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `getStatus delegates and echoes the request id`(): Unit = runBlocking {
        coEvery {
            pis.getPaymentStatus(GetPaymentStatusQuery("p-1", "tpp-1", PaymentProduct.SEPA_CREDIT_TRANSFERS))
        } returns PaymentStatus.ACSC

        val response = resource.getStatus("sepa-credit-transfers", "p-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["transactionStatus"]).isEqualTo("ACSC")
    }
}
