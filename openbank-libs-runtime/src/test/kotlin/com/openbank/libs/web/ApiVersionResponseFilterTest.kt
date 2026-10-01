// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class ApiVersionResponseFilterTest {

    private fun makeFilter(
        apiVersion: String = "1",
        deprecatedPaths: Optional<List<String>> = Optional.empty(),
        sunsetDate: Optional<String> = Optional.empty(),
        successorLinks: Optional<List<String>> = Optional.empty(),
    ) = ApiVersionResponseFilter(
        serviceName = "test-service",
        serviceVersion = "1.2.3",
        apiVersion = apiVersion,
        deprecatedPaths = deprecatedPaths,
        sunsetDate = sunsetDate,
        successorLinks = successorLinks,
    )

    private fun makeReqResp(path: String): Pair<ContainerRequestContext, MultivaluedHashMap<String, Any>> {
        val headers = MultivaluedHashMap<String, Any>()
        val uriInfo = mockk<UriInfo> { every { this@mockk.path } returns path }
        val req = mockk<ContainerRequestContext>(relaxed = true) {
            every { this@mockk.uriInfo } returns uriInfo
        }
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }
        return req to headers.also { makeFilter().filter(req, resp) }
    }

    @Test
    fun `sets version and identity headers on every response`() {
        val (_, headers) = makeReqResp("/api/v1/accounts")
        assertThat(headers.getFirst("X-API-Version") as String).isEqualTo("v1")
        assertThat(headers.getFirst("X-Service-Version") as String).isEqualTo("1.2.3")
        assertThat(headers.getFirst("X-Service-Name") as String).isEqualTo("test-service")
        assertThat(headers).containsKey("X-Request-ID")
        assertThat(headers).containsKey("X-Correlation-ID")
    }

    @Test
    fun `uses the request path major when a service exposes v1 and v2 together`() {
        val v1Headers = responseHeaders("/api/v1/products", apiVersion = "2")
        val v2Headers = responseHeaders("/api/v2/products", apiVersion = "2")

        assertThat(v1Headers.getFirst("X-API-Version")).isEqualTo("v1")
        assertThat(v2Headers.getFirst("X-API-Version")).isEqualTo("v2")
    }

    @Test
    fun `no deprecation headers when no paths configured`() {
        val (_, headers) = makeReqResp("/api/v1/accounts")
        assertThat(headers).doesNotContainKey("Deprecation")
        assertThat(headers).doesNotContainKey("Sunset")
        assertThat(headers).doesNotContainKey("Link")
    }

    @Test
    fun `deprecation headers on matching path with sunset and successor link`() {
        val headers = MultivaluedHashMap<String, Any>()
        val uriInfo = mockk<UriInfo> { every { path } returns "api/v1/accounts/123" }
        val req = mockk<ContainerRequestContext>(relaxed = true) {
            every { this@mockk.uriInfo } returns uriInfo
        }
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }

        makeFilter(
            deprecatedPaths = Optional.of(listOf("/api/v1/accounts")),
            sunsetDate = Optional.of("Sat, 01 Jan 2028 00:00:00 GMT"),
            successorLinks = Optional.of(listOf("/api/v1/accounts=>/api/v2/account-products")),
        ).filter(req, resp)

        assertThat(headers.getFirst("Deprecation") as String).isEqualTo("true")
        assertThat(headers.getFirst("Sunset") as String).isEqualTo("Sat, 01 Jan 2028 00:00:00 GMT")
        val link = headers.getFirst("Link") as String
        assertThat(link).contains("/api/v2/account-products")
        assertThat(link).contains("rel=\"successor-version\"")
    }

    @Test
    fun `no deprecation headers when path does not match configured prefix`() {
        val headers = MultivaluedHashMap<String, Any>()
        val uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/parties/abc" }
        val req = mockk<ContainerRequestContext>(relaxed = true) {
            every { this@mockk.uriInfo } returns uriInfo
        }
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }

        makeFilter(deprecatedPaths = Optional.of(listOf("/api/v1/accounts"))).filter(req, resp)

        assertThat(headers).doesNotContainKey("Deprecation")
    }

    @Test
    fun `deprecation headers without sunset when sunset-date not configured`() {
        val headers = MultivaluedHashMap<String, Any>()
        val uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/accounts/99" }
        val req = mockk<ContainerRequestContext>(relaxed = true) {
            every { this@mockk.uriInfo } returns uriInfo
        }
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }

        makeFilter(deprecatedPaths = Optional.of(listOf("/api/v1/accounts"))).filter(req, resp)

        assertThat(headers.getFirst("Deprecation") as String).isEqualTo("true")
        assertThat(headers).doesNotContainKey("Sunset")
        assertThat(headers).containsKey("Link")
    }

    private fun responseHeaders(path: String, apiVersion: String): MultivaluedHashMap<String, Any> {
        val headers = MultivaluedHashMap<String, Any>()
        val uriInfo = mockk<UriInfo> { every { this@mockk.path } returns path }
        val req = mockk<ContainerRequestContext>(relaxed = true) {
            every { this@mockk.uriInfo } returns uriInfo
        }
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }
        makeFilter(apiVersion = apiVersion).filter(req, resp)
        return headers
    }

    private fun echoedIds(
        correlationProperty: Any?,
        requestProperty: Any?,
        correlationHeader: String?,
        requestHeader: String?,
    ): MultivaluedHashMap<String, Any> {
        val headers = MultivaluedHashMap<String, Any>()
        val uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/accounts" }
        val req = mockk<ContainerRequestContext>(relaxed = true)
        every { req.uriInfo } returns uriInfo
        // Explicit receiver: inside a `mockk {}` block a bare `getProperty(name)` resolves to
        // MockK's own dynamic-property accessor, not to the JAX-RS method being stubbed.
        every { req.getProperty(ApiVersionResponseFilter.CORRELATION_ID_KEY) } returns correlationProperty
        every { req.getProperty(MDC_REQUEST_ID) } returns requestProperty
        every { req.getHeaderString(HEADER_CORRELATION_ID) } returns correlationHeader
        every { req.getHeaderString(HEADER_REQUEST_ID) } returns requestHeader
        val resp = mockk<ContainerResponseContext> { every { this@mockk.headers } returns headers }
        makeFilter().filter(req, resp)
        return headers
    }

    @Test
    fun `echoes the ids the request filter settled on, not the raw request headers`() {
        val headers = echoedIds(
            correlationProperty = "corr-settled",
            requestProperty = "req-settled",
            correlationHeader = "corr \"raw\"\nvalue",
            requestHeader = "req \"raw\"\nvalue",
        )

        assertThat(headers.getFirst("X-Correlation-ID")).isEqualTo("corr-settled")
        assertThat(headers.getFirst("X-Request-ID")).isEqualTo("req-settled")
    }

    @Test
    fun `without the request filter a well-formed header is echoed and a malformed one is replaced`() {
        val wellFormed = echoedIds(null, null, correlationHeader = "corr-1", requestHeader = "req-1")
        assertThat(wellFormed.getFirst("X-Correlation-ID")).isEqualTo("corr-1")
        assertThat(wellFormed.getFirst("X-Request-ID")).isEqualTo("req-1")

        val malformed = "x".repeat(MAX_INBOUND_ID_LENGTH + 1)
        val replaced = echoedIds(null, null, correlationHeader = "a\r\nb", requestHeader = malformed)
        assertThat(replaced.getFirst("X-Correlation-ID") as String).isNotEqualTo("a\r\nb").matches("[0-9a-f-]{36}")
        assertThat(replaced.getFirst("X-Request-ID") as String).isNotEqualTo(malformed).matches("[0-9a-f-]{36}")
    }
}
