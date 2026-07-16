// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import io.mockk.coEvery
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the bug caught wiring up ADR-0170 (app-side document signing):
 * [ScaVerificationAdapter] used to pre-check the challenge's `status == COMPLETED` via a plain
 * `GET` before calling `consume` — but a decoupled challenge only gets promoted to COMPLETED
 * *inside* `consume()` itself (`ScaService.verifyDecoupled`), so that pre-check always saw
 * PENDING and verification could never succeed. The fix calls `consume` directly, mirroring
 * customer-edge's own payment `scaGate()`.
 */
class ScaVerificationAdapterTest {

    private val client = mockk<ScaChallengeClient>()
    private val adapter = ScaVerificationAdapter(client)

    private val partyId = UUID.randomUUID()
    private val challengeId = UUID.randomUUID()

    @Test
    fun `verify succeeds via consume alone, without a prior getChallenge status check`(): Unit = runBlocking {
        coEvery {
            client.consume(
                challengeId,
                ScaConsumeClientRequest(partyId = partyId, documentSha256 = "abc123", ceremonyId = "cer-1"),
            )
        } returns Uni.createFrom().item(
            ScaChallengeClientResponse(id = challengeId, partyId = partyId, status = "COMPLETED"),
        )

        val result = adapter.verify(
            partyRef = partyId.toString(),
            evidenceRef = challengeId.toString(),
            documentSha256 = "abc123",
            ceremonyId = "cer-1",
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `verify returns false on a 4xx from consume (mismatch, already-consumed, or wrong party)`(): Unit =
        runBlocking {
            coEvery { client.consume(challengeId, any()) } returns
                Uni.createFrom().failure(WebApplicationException(Response.Status.CONFLICT))

            val result = adapter.verify(
                partyRef = partyId.toString(),
                evidenceRef = challengeId.toString(),
                documentSha256 = "abc123",
                ceremonyId = "cer-1",
            )

            assertThat(result).isFalse()
        }

    @Test
    fun `verify rethrows a non-4xx failure instead of swallowing it`() {
        coEvery { client.consume(challengeId, any()) } returns
            Uni.createFrom().failure(WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR))

        val thrown = runCatching {
            runBlocking {
                adapter.verify(
                    partyRef = partyId.toString(),
                    evidenceRef = challengeId.toString(),
                    documentSha256 = "abc123",
                    ceremonyId = "cer-1",
                )
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(WebApplicationException::class.java)
    }

    @Test
    fun `verify returns false for a malformed evidenceRef without calling the client`(): Unit = runBlocking {
        val result = adapter.verify(
            partyRef = partyId.toString(),
            evidenceRef = "not-a-uuid",
            documentSha256 = "abc123",
            ceremonyId = "cer-1",
        )

        assertThat(result).isFalse()
    }
}
