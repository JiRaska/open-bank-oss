// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class McpNativeAgePolicyTest {
    private val upstream = mockk<UpstreamClient>()
    private val party = UUID.randomUUID()
    private val now = Instant.parse("2026-11-27T00:00:00Z")

    @Test
    fun `ordinary 72 hour rule skips lookup and denied scope fails closed`() {
        val policy = McpNativeAgePolicy(upstream, ObjectMapper(), "https://party.test", false)
        assertThat(policy.eligible(party, now.minusSeconds(72 * 3600L), now)).isTrue()
        assertThat(policy.eligible(party, now.minusSeconds(60), now)).isFalse()
        verify(exactly = 0) { upstream.getToService(any(), any(), any(), any()) }
    }

    @Test
    fun `unexpired authoritative one minute exception still rejects a younger passkey`() {
        val policy = McpNativeAgePolicy(upstream, ObjectMapper(), "https://party.test", true)
        val path = "/api/v1/parties/$party/mcp-native-passkey-age-exception/effective"
        every { upstream.getToService("https://party.test", path, emptyMap(), party.toString()) } answers {
            Response.ok("""{"minimumAgeMinutes":1,"expiresAt":"2026-11-27T01:00:00Z"}""").build()
        }
        assertThat(policy.eligible(party, now.minusSeconds(59), now)).isFalse()
        assertThat(policy.eligible(party, now.minusSeconds(60), now)).isTrue()
        every { upstream.getToService("https://party.test", path, emptyMap(), party.toString()) } returns
            Response.status(404).build()
        assertThat(policy.eligible(party, now.minusSeconds(60), now)).isFalse()
    }
}
