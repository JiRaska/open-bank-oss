// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.ConsentManagementUseCase
import com.openbank.psd2.application.port.`in`.CreateConsentCommand
import com.openbank.psd2.application.port.`in`.DeleteConsentCommand
import com.openbank.psd2.application.port.`in`.GetConsentQuery
import com.openbank.psd2.application.usecase.Psd2RequestFormatException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The bespoke `/open-banking/v2` consent surface: idempotency replay on `POST`, the `tppId`
 * fail-closed check shared by every method, and the plain (non-Berlin) response envelope.
 */
class ConsentResourceTest {

    private val consentMgmt = mockk<ConsentManagementUseCase>()
    private val idempotencyStore = mockk<IdempotencyStore>()
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val resource = ConsentResource(consentMgmt, idempotencyStore, objectMapper)

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
        links = ObLinks(self = "/open-banking/v2/consents/$id"),
    )

    @Test
    fun `createConsent returns 401 CERTIFICATE_MISSING when tppId missing`(): Unit = runBlocking {
        val response = resource.createConsent(sampleRequest(), null, "req-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `createConsent requires a non-blank X-Request-ID`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { resource.createConsent(sampleRequest(), null, "", ctxWithTpp("tpp-1")) }
        }.isInstanceOf(Psd2RequestFormatException::class.java)
    }

    @Test
    fun `createConsent replays a cached response on idempotency hit`(): Unit = runBlocking {
        val cached = IdempotencyRecord("psd2:consent:tpp-1:req-1", 201, """{"consentId":"c-1"}""", OffsetDateTime.now())
        coEvery { idempotencyStore.get("psd2:consent:tpp-1:req-1") } returns cached

        val response = resource.createConsent(sampleRequest(), null, "req-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(201)
        assertThat(response.headers.getFirst("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(response.entity).isEqualTo(cached.responseBody)
        coVerify(exactly = 0) { consentMgmt.createConsent(any()) }
    }

    @Test
    fun `createConsent delegates, caches and returns 201 with Location`(): Unit = runBlocking {
        coEvery { idempotencyStore.get("psd2:consent:tpp-1:req-2") } returns null
        coEvery {
            consentMgmt.createConsent(
                CreateConsentCommand(
                    tppId = "tpp-1",
                    tppName = "My TPP",
                    request = sampleRequest(),
                    redirectUri = "https://tpp.example/cb",
                    tppTransactionId = "req-2",
                    ipAddress = null,
                ),
            )
        } returns sampleConsentResponse("c-1")
        coEvery { idempotencyStore.save("psd2:consent:tpp-1:req-2", 201, any()) } returns Unit

        val response = resource.createConsent(
            sampleRequest(),
            "https://tpp.example/cb",
            "req-2",
            ctxWithTpp("tpp-1", "My TPP"),
        )

        assertThat(response.status).isEqualTo(201)
        assertThat(response.headers.getFirst("Location")).isEqualTo("/open-banking/v2/consents/c-1")
        coVerify(exactly = 1) { idempotencyStore.save("psd2:consent:tpp-1:req-2", 201, any()) }
    }

    @Test
    fun `createConsent defaults tppName to tppId when TPP-Name header absent`(): Unit = runBlocking {
        coEvery { idempotencyStore.get("psd2:consent:tpp-2:req-3") } returns null
        coEvery {
            consentMgmt.createConsent(
                match { it.tppName == "tpp-2" },
            )
        } returns sampleConsentResponse("c-2")
        coEvery { idempotencyStore.save(any(), any(), any()) } returns Unit

        resource.createConsent(sampleRequest(), null, "req-3", ctxWithTpp("tpp-2", null))

        coVerify(exactly = 1) { consentMgmt.createConsent(match { it.tppName == "tpp-2" }) }
    }

    @Test
    fun `getConsent returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getConsent("c-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getConsent delegates and returns 200`(): Unit = runBlocking {
        coEvery { consentMgmt.getConsent(GetConsentQuery("c-1", "tpp-1")) } returns sampleConsentResponse("c-1")

        val response = resource.getConsent("c-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(sampleConsentResponse("c-1"))
    }

    @Test
    fun `getConsentStatus returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.getConsentStatus("c-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `getConsentStatus wraps the status name`(): Unit = runBlocking {
        coEvery { consentMgmt.getConsentStatus(GetConsentQuery("c-1", "tpp-1")) } returns ConsentStatusOb.VALID

        val response = resource.getConsentStatus("c-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["consentStatus"]).isEqualTo("VALID")
    }

    @Test
    fun `deleteConsent returns 401 when tppId missing`(): Unit = runBlocking {
        val response = resource.deleteConsent("c-1", ctxWithTpp(null))
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `deleteConsent delegates and returns 204`(): Unit = runBlocking {
        coEvery { consentMgmt.deleteConsent(DeleteConsentCommand("c-1", "tpp-1")) } returns Unit

        val response = resource.deleteConsent("c-1", ctxWithTpp("tpp-1"))

        assertThat(response.status).isEqualTo(204)
        coVerify(exactly = 1) { consentMgmt.deleteConsent(DeleteConsentCommand("c-1", "tpp-1")) }
    }
}
