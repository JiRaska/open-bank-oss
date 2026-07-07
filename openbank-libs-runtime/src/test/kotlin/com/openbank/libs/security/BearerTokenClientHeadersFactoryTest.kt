// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.MultivaluedHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BearerTokenClientHeadersFactoryTest {

    private fun factoryWith(tokenProvider: Instance<ServiceTokenProvider>): BearerTokenClientHeadersFactory =
        BearerTokenClientHeadersFactory().apply { this.tokenProvider = tokenProvider }

    private fun unresolvableProvider(): Instance<ServiceTokenProvider> = mockk { every { isResolvable } returns false }

    @Test
    fun `prefers the CDI ServiceTokenProvider when one is resolvable`() {
        val provider = mockk<ServiceTokenProvider> { every { getToken() } returns "s2s-token" }
        val tokenProvider = mockk<Instance<ServiceTokenProvider>> {
            every { isResolvable } returns true
            every { get() } returns provider
        }
        val incoming = MultivaluedHashMap<String, String>().apply {
            putSingle("Authorization", "Bearer end-user-token")
        }
        val outgoing = MultivaluedHashMap<String, String>()

        val result = factoryWith(tokenProvider).update(incoming, outgoing)

        assertThat(result.getFirst("Authorization")).isEqualTo("Bearer s2s-token")
    }

    @Test
    fun `falls back to passing through the inbound Authorization header (act-as flow)`() {
        val incoming = MultivaluedHashMap<String, String>().apply {
            putSingle("Authorization", "Bearer end-user-token")
        }
        val outgoing = MultivaluedHashMap<String, String>()

        val result = factoryWith(unresolvableProvider()).update(incoming, outgoing)

        assertThat(result.getFirst("Authorization")).isEqualTo("Bearer end-user-token")
    }

    @Test
    fun `sends no Authorization header when neither a provider nor an inbound header exists`() {
        val incoming = MultivaluedHashMap<String, String>()
        val outgoing = MultivaluedHashMap<String, String>()

        val result = factoryWith(unresolvableProvider()).update(incoming, outgoing)

        assertThat(result.containsKey("Authorization")).isFalse()
    }

    @Test
    fun `never overwrites an Authorization header already set on the outgoing request`() {
        val provider = mockk<ServiceTokenProvider> { every { getToken() } returns "s2s-token" }
        val tokenProvider = mockk<Instance<ServiceTokenProvider>> {
            every { isResolvable } returns true
            every { get() } returns provider
        }
        val incoming = MultivaluedHashMap<String, String>()
        val outgoing = MultivaluedHashMap<String, String>().apply { putSingle("Authorization", "Bearer preset") }

        val result = factoryWith(tokenProvider).update(incoming, outgoing)

        assertThat(result.getFirst("Authorization")).isEqualTo("Bearer preset")
    }

    @Test
    fun `propagates correlation and request ids from inbound to outgoing`() {
        val incoming = MultivaluedHashMap<String, String>().apply {
            putSingle("X-Correlation-ID", "corr-1")
            putSingle("X-Request-ID", "req-1")
        }
        val outgoing = MultivaluedHashMap<String, String>()

        val result = factoryWith(unresolvableProvider()).update(incoming, outgoing)

        assertThat(result.getFirst("X-Correlation-ID")).isEqualTo("corr-1")
        assertThat(result.getFirst("X-Request-ID")).isEqualTo("req-1")
    }

    @Test
    fun `does not overwrite correlation ids already present on the outgoing request`() {
        val incoming = MultivaluedHashMap<String, String>().apply { putSingle("X-Correlation-ID", "corr-inbound") }
        val outgoing = MultivaluedHashMap<String, String>().apply { putSingle("X-Correlation-ID", "corr-outgoing") }

        val result = factoryWith(unresolvableProvider()).update(incoming, outgoing)

        assertThat(result.getFirst("X-Correlation-ID")).isEqualTo("corr-outgoing")
    }
}
