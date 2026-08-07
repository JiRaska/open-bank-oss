// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Post-onboarding marketing-consent revocation (mobile app Profile screen). Split into its own
 * class rather than appended to the already-large [CustomerEdgeResourceTest].
 */
class CustomerEdgeResourceConsentTest {

    private fun newResource(sub: UUID, upstream: UpstreamClient): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns null
            every { subject } returns sub.toString()
        }
        objectMapper = ObjectMapper()
        partyServiceUrl = "http://party"
    }

    @Test
    fun `updateConsent PATCHes marketingConsent scoped to the caller's own party id`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val bodySlot = slot<String>()
        every {
            upstream.patch(match { it.contains("/api/v1/parties/$sub/consent") }, sub.toString(), capture(bodySlot))
        } returns Response.ok("""{"id":"$sub","consentMarketing":false}""").build()
        val resource = newResource(sub, upstream)

        val resp = resource.updateConsent("""{"marketingConsent":false}""")

        assertThat(resp.status).isEqualTo(200)
        assertThat(bodySlot.captured).isEqualTo("""{"marketingConsent":false}""")
        verify(exactly = 1) { upstream.patch(any(), sub.toString(), any()) }
    }

    @Test
    fun `updateConsent rejects a body missing marketingConsent without calling upstream`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resource = newResource(sub, upstream)

        val resp = resource.updateConsent("{}")

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { upstream.patch(any(), any(), any()) }
    }

    @Test
    fun `updateConsent rejects a non-boolean marketingConsent value`() {
        val sub = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resource = newResource(sub, upstream)

        val resp = resource.updateConsent("""{"marketingConsent":"yes"}""")

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { upstream.patch(any(), any(), any()) }
    }
}
