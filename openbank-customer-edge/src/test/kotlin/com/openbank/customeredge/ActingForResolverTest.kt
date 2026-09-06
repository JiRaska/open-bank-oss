// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The profile switch (ADR-0284 D4) is FAIL-CLOSED: every branch that is not "party-service says
 * there is an active mandate" is a 403. Each test below names the failure it refuses.
 */
class ActingForResolverTest {

    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val partyBase = "http://party-service.party.svc:8111"
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)

    private fun resolver(upstream: UpstreamClient, enabled: Boolean = true) =
        ActingForResolver(upstream, ObjectMapper(), clock, partyBase, enabled)

    private fun actingFor(vararg ids: UUID): Response = Response.ok(
        ids.joinToString(",", "[", "]") {
            """{"partyId":"$it","partyType":"COMPANY","legalName":"Příklad s.r.o.","status":"ACTIVE","kycStatus":"APPROVED","mandate":{"role":"LEGAL_REPRESENTATIVE","authority":"SOLE"}}"""
        },
    ).build()

    @Test
    fun `no header means the personal profile and party-service is never asked`() {
        val upstream = mockk<UpstreamClient>()
        assertThat(resolver(upstream).resolve(human, null)).isEqualTo(human)
        assertThat(resolver(upstream).resolve(human, "  ")).isEqualTo(human)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `a header naming an entity with an active mandate switches the party`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$human/acting-for", human.toString()) } returns
            actingFor(company)
        assertThat(resolver(upstream).resolve(human, company.toString())).isEqualTo(company)
    }

    @Test
    fun `an entity the human has no mandate for is refused`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns actingFor(company)
        assertThatThrownBy { resolver(upstream).resolve(human, stranger.toString()) }
            .isInstanceOf(ForbiddenException::class.java)
            .hasMessageContaining("no active mandate")
    }

    @Test
    fun `an unreachable party-service, a non-200 and a garbage body all refuse - never fall open`() {
        val down = mockk<UpstreamClient>()
        every { down.get(any(), any()) } throws IllegalStateException("connection refused")
        assertThatThrownBy {
            resolver(down).resolve(human, company.toString())
        }.isInstanceOf(ForbiddenException::class.java)

        val error = mockk<UpstreamClient>()
        every { error.get(any(), any()) } returns Response.status(502).entity("bad gateway").build()
        assertThatThrownBy {
            resolver(error).resolve(human, company.toString())
        }.isInstanceOf(ForbiddenException::class.java)

        val garbage = mockk<UpstreamClient>()
        every { garbage.get(any(), any()) } returns Response.ok("<html>").build()
        assertThatThrownBy {
            resolver(garbage).resolve(human, company.toString())
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a malformed header and the kill switch both refuse rather than ignore the header`() {
        val upstream = mockk<UpstreamClient>()
        assertThatThrownBy {
            resolver(upstream).resolve(human, "not-a-uuid")
        }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            resolver(upstream, enabled = false).resolve(human, company.toString())
        }.isInstanceOf(ForbiddenException::class.java)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `a positive answer is cached so the switcher costs one upstream read, not one per request`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns actingFor(company)
        val r = resolver(upstream)
        repeat(5) { assertThat(r.resolve(human, company.toString())).isEqualTo(company) }
        verify(exactly = 1) { upstream.get(any(), any()) }
    }
}
