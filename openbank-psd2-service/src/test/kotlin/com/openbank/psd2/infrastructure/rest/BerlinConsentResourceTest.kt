// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.ConsentManagementUseCase
import com.openbank.psd2.application.port.`in`.CreateConsentCommand
import com.openbank.psd2.application.port.`in`.DeleteConsentCommand
import com.openbank.psd2.application.port.`in`.GetConsentQuery
import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.ObAccess
import com.openbank.psd2.domain.model.ObConsentRequest
import com.openbank.psd2.domain.model.ObConsentResponse
import com.openbank.psd2.domain.model.ObLinks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Berlin Group XS2A `/v1/consents` surface: mandatory `X-Request-ID`, idempotent creation and the
 * echoed request id on every response.
 */
class BerlinConsentResourceTest {

    private val consentMgmt = mockk<ConsentManagementUseCase>()
    private val idempotencyStore = mockk<IdempotencyStore>()
    private val objectMapper = ObjectMapper()
    private val resource = BerlinConsentResource(consentMgmt, idempotencyStore, objectMapper)

    private fun ctxWithTpp(tppId: String?, tppName: String? = null): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>()
        every { ctx.getProperty("tppId") } returns tppId
        every { ctx.getHeaderString("TPP-Name") } returns tppName
        return ctx
    }

    private fun sampleRequest() = ObConsentRequest(
        access = ObAccess(accounts = null, balances = null, transactions = null, additionalInformation = null),
        recurringIndicator = true,
        validUntil = LocalDate.of(2024, 6, 1),
        frequencyPerDay = 4,
    )

    private fun sampleConsentResponse(id: String) = ObConsentResponse(
        consentId = id,
        consentStatus = ConsentStatusOb.RECEIVED,
        access = ObAccess(accounts = null, balances = null, transactions = null, additionalInformation = null),
        recurringIndicator = true,
        validUntil = LocalDate.of(2024, 6, 1),
        frequencyPerDay = 4,
        lastActionDate = LocalDate.of(2024, 1, 1),
        links = ObLinks(self = "/v1/consents/$id", scaRedirect = "https://sca.example/$id"),
    )

    @Test
    fun `createConsent returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.createConsent(sampleRequest(), "req-1", null, null, ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `createConsent returns 400 FORMAT_ERROR when X-Request-ID missing`(): Unit = runBlocking {
        val response = resource.createConsent(sampleRequest(), null, null, null, ctxWithTpp("tpp-1"))
        assertThat(response.status).isEqualTo(400)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = body["tppMessages"] as List<Map<String, Any?>>
        assertThat(messages[0]["code"]).isEqualTo("FORMAT_ERROR")
    }

    @Test
    fun `createConsent replays a cached response with the replay header`(): Unit = runBlocking {
        val cacheKey = "psd2:v1:consent:tpp-1:req-1"
        val cached = IdempotencyRecord(cacheKey, 201, """{"consentId":"c-1"}""", OffsetDateTime.now())
        coEvery { idempotencyStore.get(cacheKey) } returns cached

        val response = resource.createConsent(sampleRequest(), "req-1", null, null, ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
        assertThat(response.headers.getFirst("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
        coVerify(exactly = 0) { consentMgmt.createConsent(any()) }
    }

    @Test
    fun `createConsent delegates, saves idempotency and returns Berlin created body`(): Unit = runBlocking {
        val cacheKey = "psd2:v1:consent:tpp-1:req-2"
        coEvery { idempotencyStore.get(cacheKey) } returns null
        coEvery {
            consentMgmt.createConsent(
                CreateConsentCommand(
                    tppId = "tpp-1",
                    tppName = "tpp-1",
                    request = sampleRequest(),
                    redirectUri = "https://tpp.example/cb",
                    tppTransactionId = "req-2",
                    ipAddress = "10.0.0.1",
                ),
            )
        } returns sampleConsentResponse("c-1")
        coEvery { idempotencyStore.save(cacheKey, 201, any()) } returns Unit

        val response = resource.createConsent(
            sampleRequest(),
            "req-2",
            "https://tpp.example/cb",
            "10.0.0.1",
            ctxWithTpp("tpp-1"),
        )

        assertThat(response.status).isEqualTo(201)
        assertThat(response.headers.getFirst("Location")).isEqualTo("/v1/consents/c-1")
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-2")
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["consentId"]).isEqualTo("c-1")
    }

    @Test
    fun `getConsent returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getConsent("c-1", "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getConsent echoes request id and renders Berlin consent information`(): Unit = runBlocking {
        coEvery { consentMgmt.getConsent(GetConsentQuery("c-1", "tpp-1")) } returns sampleConsentResponse("c-1")

        val response = resource.getConsent("c-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
    }

    @Test
    fun `getConsentStatus returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getConsentStatus("c-1", "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getConsentStatus wraps the Berlin lowerCamel status`(): Unit = runBlocking {
        coEvery { consentMgmt.getConsentStatus(GetConsentQuery("c-1", "tpp-1")) } returns ConsentStatusOb.VALID

        val response = resource.getConsentStatus("c-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["consentStatus"]).isEqualTo("valid")
    }

    @Test
    fun `deleteConsent returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.deleteConsent("c-1", "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `deleteConsent delegates and returns 204 with echoed request id`(): Unit = runBlocking {
        coEvery { consentMgmt.deleteConsent(DeleteConsentCommand("c-1", "tpp-1")) } returns Unit

        val response = resource.deleteConsent("c-1", "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(204)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("req-1")
    }
}
