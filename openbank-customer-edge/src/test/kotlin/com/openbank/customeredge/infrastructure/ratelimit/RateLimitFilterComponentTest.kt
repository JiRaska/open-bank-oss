// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.ratelimit

import io.quarkus.test.InjectMock
import io.quarkus.test.component.QuarkusComponentTest
import io.quarkus.test.component.TestConfigProperty
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/** Actual ArC/config injection, without starting HTTP, Redis, Kafka or a Quarkus application. */
@QuarkusComponentTest(RateLimitFilter::class)
@TestConfigProperty(key = "openbank.rate-limit.per-party.requests-per-minute", value = "7")
class RateLimitFilterComponentTest {
    @Inject
    lateinit var filter: RateLimitFilter

    @InjectMock
    lateinit var limiter: RateLimiter

    @InjectMock
    lateinit var jwt: JsonWebToken

    @Test
    fun `injected nondefault quota permits an allowed party`() {
        Mockito.`when`(jwt.getClaim<String>("party_id")).thenReturn("synthetic-party")
        Mockito.`when`(limiter.isAllowed("synthetic-party", LIMIT)).thenReturn(true)
        val context = Mockito.mock(ContainerRequestContext::class.java)

        filter.filter(context)

        Mockito.verify(limiter).isAllowed("synthetic-party", LIMIT)
        Mockito.verifyNoInteractions(context)
    }

    @Test
    fun `injected nondefault quota produces the documented rejection`() {
        Mockito.`when`(jwt.getClaim<String>("party_id")).thenReturn("synthetic-party")
        Mockito.`when`(limiter.isAllowed("synthetic-party", LIMIT)).thenReturn(false)
        val context = Mockito.mock(ContainerRequestContext::class.java)

        filter.filter(context)

        Mockito.verify(limiter).isAllowed("synthetic-party", LIMIT)
        val captured = ArgumentCaptor.forClass(Response::class.java)
        Mockito.verify(context).abortWith(captured.capture())
        captured.value.use { response ->
            assertThat(response.status).isEqualTo(429)
            assertThat(response.getHeaderString("X-RateLimit-Limit")).isEqualTo("7")
            assertThat(response.getHeaderString("X-RateLimit-Window")).isEqualTo("60s")
            assertThat(response.getHeaderString("Retry-After")).isEqualTo("60")
            assertThat(response.entity).isEqualTo(
                mapOf("code" to "RATE_LIMIT_EXCEEDED", "message" to "Too many requests. Limit: 7/min."),
            )
        }
    }

    @Test
    fun `missing party claim uses the injected subject`() {
        Mockito.`when`(jwt.subject).thenReturn("synthetic-subject")
        Mockito.`when`(limiter.isAllowed("synthetic-subject", LIMIT)).thenReturn(true)
        val context = Mockito.mock(ContainerRequestContext::class.java)

        filter.filter(context)

        Mockito.verify(limiter).isAllowed("synthetic-subject", LIMIT)
        Mockito.verifyNoInteractions(context)
    }

    @Test
    fun `anonymous request never reaches quota storage`() {
        val context = Mockito.mock(ContainerRequestContext::class.java)
        filter.filter(context)
        Mockito.verifyNoInteractions(limiter, context)
    }

    private companion object {
        const val LIMIT = 7
    }
}
