// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.ExternalDisclosureResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ExternalDisclosureResourceTest {
    private val upstream = mockk<UpstreamClient>()
    private val serviceUrl = "http://delegation-service.delegation.svc:8126"
    private val disclosureId = UUID.randomUUID()

    private fun resource() = ExternalDisclosureResource(upstream).apply { delegationServiceUrl = serviceUrl }

    @Test
    fun `OTP proxy never exposes an upstream unavailable disclosure as a different state`() {
        every { upstream.postAnonymous(any(), any()) } returns Response.status(403).entity("sensitive").build()

        val response = resource().verifyOtp(disclosureId, "{\"linkSecret\":\"secret\",\"otp\":\"123456\"}")

        assertThat(response.status).isEqualTo(404)
        assertThat(response.entity.toString()).contains("external disclosure unavailable").doesNotContain("sensitive")
    }

    @Test
    fun `content proxy preserves only sealed PDF bytes`() {
        val url = slot<String>()
        val sealed = byteArrayOf(37, 80, 68, 70)
        every { upstream.postRaw(capture(url), any(), "application/pdf") } returns
            Response.ok(sealed, "application/pdf").build()

        val response = resource().content(disclosureId, "{\"linkSecret\":\"secret\"}")

        assertThat(url.captured).isEqualTo("$serviceUrl/api/v1/external-disclosures/$disclosureId/content")
        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity).isEqualTo(sealed)
        assertThat(response.mediaType.toString()).isEqualTo("application/pdf")
    }
}
