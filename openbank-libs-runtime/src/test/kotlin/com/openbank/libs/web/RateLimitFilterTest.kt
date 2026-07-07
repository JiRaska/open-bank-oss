// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RateLimitFilterTest {

    private fun requestFor(path: String): ContainerRequestContext {
        val uriInfo = mockk<UriInfo> { every { this@mockk.path } returns path }
        return mockk(relaxed = true) { every { this@mockk.uriInfo } returns uriInfo }
    }

    @Test
    fun `disabled filter never touches the request`() {
        val filter = RateLimitFilter(maxConcurrent = 1, enabled = false)
        val req = requestFor("/api/v1/accounts")

        filter.filter(req)

        verify(exactly = 0) { req.abortWith(any()) }
        verify(exactly = 0) { req.setProperty(any(), any()) }
    }

    @Test
    fun `health endpoints under q are exempt from the limit`() {
        val filter = RateLimitFilter(maxConcurrent = 1, enabled = true)
        val first = requestFor("/q/health")
        val second = requestFor("/q/metrics")

        filter.filter(first)
        filter.filter(second)

        verify(exactly = 0) { first.abortWith(any()) }
        verify(exactly = 0) { second.abortWith(any()) }
    }

    @Test
    fun `acquires a permit and marks the request when under the limit`() {
        val filter = RateLimitFilter(maxConcurrent = 2, enabled = true)
        val req = requestFor("/api/v1/accounts")

        filter.filter(req)

        verify(exactly = 0) { req.abortWith(any()) }
        verify { req.setProperty("rate-limit-acquired", true) }
    }

    @Test
    fun `rejects with 429 once concurrent capacity is exhausted`() {
        val filter = RateLimitFilter(maxConcurrent = 1, enabled = true)
        filter.filter(requestFor("/api/v1/accounts")) // consumes the only permit

        val overflow = requestFor("/api/v1/transactions")
        val responseSlot = mutableListOf<jakarta.ws.rs.core.Response>()
        every { overflow.abortWith(any()) } answers { responseSlot.add(firstArg()) }

        filter.filter(overflow)

        assertThat(responseSlot).hasSize(1)
        val response = responseSlot.first()
        assertThat(response.status).isEqualTo(429)
        assertThat(response.getHeaderString("Retry-After")).isEqualTo("1")
        assertThat(response.getHeaderString("X-RateLimit-Remaining")).isEqualTo("0")
        verify(exactly = 0) { overflow.setProperty("rate-limit-acquired", true) }
    }

    @Test
    fun `response filter releases the permit and reports remaining capacity`() {
        val filter = RateLimitFilter(maxConcurrent = 3, enabled = true)
        val req = requestFor("/api/v1/accounts")
        filter.filter(req)
        every { req.getProperty("rate-limit-acquired") } returns true

        val headers = MultivaluedHashMap<String, Any>()
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }
        filter.filter(req, resp)

        assertThat(headers.getFirst("X-RateLimit-Limit")).isEqualTo(3)
        assertThat(headers.getFirst("X-RateLimit-Remaining")).isEqualTo(3)
    }

    @Test
    fun `response filter is a no-op when no permit was acquired`() {
        val filter = RateLimitFilter(maxConcurrent = 1, enabled = false)
        val req = requestFor("/api/v1/accounts")
        every { req.getProperty("rate-limit-acquired") } returns null

        val headers = MultivaluedHashMap<String, Any>()
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }
        filter.filter(req, resp)

        assertThat(headers).isEmpty()
    }
}
