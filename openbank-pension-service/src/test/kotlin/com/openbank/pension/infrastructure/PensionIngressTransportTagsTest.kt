// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure

import com.openbank.pension.infrastructure.rest.PensionIngressTransportTags
import io.mockk.every
import io.mockk.mockk
import io.quarkus.micrometer.runtime.HttpServerMetricsTagsContributor
import io.vertx.core.http.HttpServerRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PensionIngressTransportTagsTest {
    private val contributor = PensionIngressTransportTags()

    @Test
    fun `tags the actual plaintext listener without trusting forwarded headers`() {
        val request = mockk<HttpServerRequest> {
            every { isSSL } returns false
            every { getHeader("X-Forwarded-Proto") } returns "https"
        }
        val context = mockk<HttpServerMetricsTagsContributor.Context> {
            every { request() } returns request
        }

        assertThat(contributor.contribute(context).first { it.key == "pension_transport" }.value)
            .isEqualTo("plain")
    }

    @Test
    fun `tags the actual TLS listener`() {
        val request = mockk<HttpServerRequest> {
            every { isSSL } returns true
        }
        val context = mockk<HttpServerMetricsTagsContributor.Context> {
            every { request() } returns request
        }

        assertThat(contributor.contribute(context).first { it.key == "pension_transport" }.value)
            .isEqualTo("tls")
    }
}
