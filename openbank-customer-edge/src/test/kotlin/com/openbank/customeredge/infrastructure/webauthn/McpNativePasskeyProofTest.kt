// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.webauthn4j.util.Base64UrlUtil
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class McpNativePasskeyProofTest {
    private val now = Instant.parse("2026-11-26T00:00:00Z")
    private val binding = McpNativePasskeyBinding(
        "1".repeat(64),
        UUID.fromString("80c16597-3f4a-44eb-8fcf-395bdef9ee06"),
        "synthetic-customer",
        "synthetic-mcp",
        McpNativeParBinding.Dpop("A".repeat(43)),
        "2".repeat(64),
        now.plusSeconds(60).epochSecond,
    )
    private val challenges = mockk<McpNativePasskeyChallengeStore>()
    private val credentials = mockk<WebAuthnStore>()
    private val metrics = SimpleMeterRegistry()
    private val service = McpNativePasskeyProofService(
        challenges,
        credentials,
        ObjectMapper(),
        Clock.fixed(now, ZoneOffset.UTC),
        metrics,
        RP,
        ORIGIN,
    )

    @Test
    fun `begin stores an opaque challenge only for an unexpired connect`() {
        every { challenges.save(any(), binding) } returns Unit
        val challenge = requireNotNull(service.begin(binding))
        assertThat(challenge.rpId).isEqualTo("example.test")
        assertThat(challenge.challenge).hasSize(43)
        verify(exactly = 1) { challenges.save(challenge.challenge, binding) }
        assertThat(service.begin(binding.copy(expiresAtEpochSecond = now.epochSecond))).isNull()
    }

    @Test
    fun `completion requires the challenged connect and exact customer credential`() {
        every { challenges.claim(CHALLENGE) } returns binding andThen null
        val hosted = binding.copy(parBinding = McpNativeParBinding.ConfidentialClient)
        assertThat(service.complete(hosted, assertion())).isNull()
        assertThat(service.complete(binding, assertion())).isNull()
        verify(exactly = 0) { credentials.find(any()) }
        assertReason("CONTEXT_MISMATCH")
        assertReason("CHALLENGE_MISSING")
    }

    @Test
    fun `Redis binding serialization distinguishes hosted PAR from DPoP without synthesizing a key`() {
        val mapper = jacksonObjectMapper()
        val hosted = binding.copy(parBinding = McpNativeParBinding.ConfidentialClient)
        for (fixture in listOf(binding, hosted)) {
            val wire = mapper.writeValueAsString(fixture)
            assertThat(mapper.readValue(wire, McpNativePasskeyBinding::class.java)).isEqualTo(fixture)
        }
        assertThat(mapper.writeValueAsString(hosted)).contains("confidential_client").doesNotContain("thumbprint")
    }

    @Test
    fun `credential age and durable assertion commit are required for bank proof`() {
        val old = credential(now.minus(Duration.ofHours(72)).toEpochMilli())
        val young = credential(now.minusSeconds(60).toEpochMilli())
        val unknown = credential(null)
        every { challenges.claim(CHALLENGE) } returns binding
        every { credentials.find("AA") } returnsMany listOf(unknown, young, old, old)
        every { credentials.recordAssertion(any(), 7L) } returnsMany listOf(true, true, false, true)
        mockkObject(WebAuthnAssertionVerifier)
        try {
            every { WebAuthnAssertionVerifier.verify(any(), any(), CHALLENGE, RP, ORIGIN) } returns 7L
            assertThat(service.complete(binding, assertion())).isNull()
            assertThat(service.complete(binding, assertion())).isNull()
            assertThat(service.complete(binding, assertion())).isNull()
            val proof = requireNotNull(service.complete(binding, assertion()))
            assertThat(proof.binding).isEqualTo(binding)
            assertThat(proof.credentialReference).hasSize(64)
            assertThat(proof.firstVerifiedAtEpochMillis).isEqualTo(old.firstVerifiedAtEpochMillis)
            assertThat(proof.assertedAtEpochMillis).isEqualTo(now.toEpochMilli())
            assertReason("AGE_MISSING")
            assertReason("AGE_TOO_YOUNG")
            assertReason("ASSERTION_CONFLICT")
            assertThat(metrics.meters.flatMap { it.id.tags }.map { it.key }.distinct()).containsExactly("reason")
        } finally {
            unmockkObject(WebAuthnAssertionVerifier)
        }
    }

    private fun assertReason(reason: String) {
        val count = metrics.get("openbank.edge.mcp.native.proof.rejected").tag("reason", reason).counter().count()
        assertThat(count).isEqualTo(1.0)
    }

    private fun credential(age: Long?) = RegisteredCredential(
        "AA",
        binding.partyId.toString(),
        binding.keycloakUserId,
        "AA==",
        6,
        age,
    )

    private fun assertion(): AuthCompleteRequestDto {
        val clientData = Base64UrlUtil.encodeToString("""{"challenge":"$CHALLENGE"}""".toByteArray())
        return AuthCompleteRequestDto("AA", "AA", clientData, "AA", "AA")
    }

    private companion object {
        const val RP = "example.test"
        const val ORIGIN = "https://example.test"
        val CHALLENGE = "A".repeat(43)
    }
}
