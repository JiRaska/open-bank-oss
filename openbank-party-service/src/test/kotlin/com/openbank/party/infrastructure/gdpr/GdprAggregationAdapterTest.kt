// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.gdpr

import com.openbank.party.application.port.out.GdprAggregationAuthException
import com.openbank.party.infrastructure.client.CardServiceRestClient
import com.openbank.party.infrastructure.client.KycServiceRestClient
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
 * Covers [GdprAggregationAdapter] — the GDPR Art. 15 orchestration hop from party-service out to
 * kyc-service (`GET /api/v1/kyc/cases/party/{partyId}`) and card-issuance-service
 * (`GET /api/v1/cards/party/{partyId}`), issue #268 / ADR-0118 §6.
 *
 * These tests exist for the distinction the previous suite could not express. It drove a loopback
 * HTTP server and asserted only "200 → data" and "error → degrade", so a **401 on every call in
 * every deployed environment** (the old raw `java.net.http` client sent no Authorization header,
 * and both endpoints are `@RolesAllowed`) was indistinguishable from a subject who genuinely holds
 * no KYC case and no cards. Both read as a successful, empty export.
 *
 * So the contract has two halves and each needs its own assertion:
 *  - absent (404) or unavailable (5xx, connect failure) → degrade to null/empty, export ships;
 *  - refused (401/403) → [GdprAggregationAuthException], export must NOT ship.
 */
class GdprAggregationAdapterTest {

    private val kycClient: KycServiceRestClient = mockk()
    private val cardClient: CardServiceRestClient = mockk()
    private val adapter = GdprAggregationAdapter(kycClient, cardClient)

    private val partyId: UUID = UUID.randomUUID()

    private fun <T> failing(status: Int): Uni<T> = Uni.createFrom().failure(WebApplicationException(status))

    // ──── fetchKycData ────────────────────────────────────────────────────────

    @Test
    fun `fetchKycData returns the case when kyc-service responds 200`(): Unit = runBlocking {
        every { kycClient.getCaseByParty(partyId) } returns
            Uni.createFrom().item(mapOf<String, Any?>("status" to "APPROVED", "riskLevel" to "LOW"))

        val result = adapter.fetchKycData(partyId)

        assertThat(result).isNotNull
        assertThat(result!!["status"]).isEqualTo("APPROVED")
        assertThat(result["riskLevel"]).isEqualTo("LOW")
    }

    @Test
    fun `fetchKycData degrades to null when the party genuinely has no KYC case (404)`(): Unit = runBlocking {
        every { kycClient.getCaseByParty(partyId) } returns failing(404)

        assertThat(adapter.fetchKycData(partyId)).isNull()
    }

    @Test
    fun `fetchKycData degrades to null when kyc-service is unavailable (503)`(): Unit = runBlocking {
        every { kycClient.getCaseByParty(partyId) } returns failing(503)

        assertThat(adapter.fetchKycData(partyId)).isNull()
    }

    @Test
    fun `fetchKycData degrades to null when kyc-service is unreachable`(): Unit = runBlocking {
        every { kycClient.getCaseByParty(partyId) } returns
            Uni.createFrom().failure(java.net.ConnectException("connection refused"))

        assertThat(adapter.fetchKycData(partyId)).isNull()
    }

    @Test
    fun `fetchKycData THROWS on 401 instead of reporting the subject has no KYC case`(): Unit = runBlocking {
        every { kycClient.getCaseByParty(partyId) } returns failing(401)

        assertThatThrownBy { runBlocking { adapter.fetchKycData(partyId) } }
            .isInstanceOf(GdprAggregationAuthException::class.java)
            .hasMessageContaining("kyc-service")
            .hasMessageContaining("401")
    }

    @Test
    fun `fetchKycData THROWS on 403`(): Unit = runBlocking {
        every { kycClient.getCaseByParty(partyId) } returns failing(403)

        assertThatThrownBy { runBlocking { adapter.fetchKycData(partyId) } }
            .isInstanceOf(GdprAggregationAuthException::class.java)
    }

    // ──── fetchCardData ───────────────────────────────────────────────────────

    @Test
    fun `fetchCardData returns the cards when card-issuance-service responds 200`(): Unit = runBlocking {
        every { cardClient.listByParty(partyId) } returns
            Uni.createFrom().item(listOf(mapOf<String, Any?>("maskedPan" to "**** **** **** 1234")))

        val result = adapter.fetchCardData(partyId)

        assertThat(result).hasSize(1)
        assertThat(result.single()["maskedPan"]).isEqualTo("**** **** **** 1234")
    }

    @Test
    fun `fetchCardData returns an empty list when the party holds no cards`(): Unit = runBlocking {
        every { cardClient.listByParty(partyId) } returns Uni.createFrom().item(emptyList())

        assertThat(adapter.fetchCardData(partyId)).isEmpty()
    }

    @Test
    fun `fetchCardData degrades to empty when card-issuance-service errors (500)`(): Unit = runBlocking {
        every { cardClient.listByParty(partyId) } returns failing(500)

        assertThat(adapter.fetchCardData(partyId)).isEmpty()
    }

    @Test
    fun `fetchCardData THROWS on 401 instead of reporting the subject holds no cards`(): Unit = runBlocking {
        every { cardClient.listByParty(partyId) } returns failing(401)

        assertThatThrownBy { runBlocking { adapter.fetchCardData(partyId) } }
            .isInstanceOf(GdprAggregationAuthException::class.java)
            .hasMessageContaining("card-issuance-service")
    }

    @Test
    fun `fetchCardData THROWS on 403`(): Unit = runBlocking {
        every { cardClient.listByParty(partyId) } returns failing(403)

        assertThatThrownBy { runBlocking { adapter.fetchCardData(partyId) } }
            .isInstanceOf(GdprAggregationAuthException::class.java)
    }
}
