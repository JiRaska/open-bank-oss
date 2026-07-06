// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** RFC 8594 deprecation signalling on the bespoke `/open-banking/v2` surface only (ADR-0090 P4). */
class BespokeDeprecationFilterTest {

    private val filter = BespokeDeprecationFilter()

    private fun requestFor(path: String): ContainerRequestContext {
        val req = mockk<ContainerRequestContext>()
        val uriInfo = mockk<UriInfo>()
        every { uriInfo.path } returns path
        every { req.uriInfo } returns uriInfo
        return req
    }

    @Test
    fun `Berlin v1 responses are left untouched`() {
        val req = requestFor("v1/accounts")
        val resp = mockk<ContainerResponseContext>()

        filter.filter(req, resp)

        verify(exactly = 0) { resp.headers }
    }

    @Test
    fun `bespoke open-banking responses get Deprecation, Sunset and successor Link headers`() {
        val req = requestFor("open-banking/v2/accounts")
        val resp = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { resp.headers } returns headers

        filter.filter(req, resp)

        assertThat(headers.getFirst("Deprecation")).isEqualTo("true")
        assertThat(headers.getFirst("Sunset")).isEqualTo("Wed, 31 Dec 2031 23:59:59 GMT")
        assertThat(headers["Link"]).contains("</v1>; rel=\"successor-version\"")
    }
}
