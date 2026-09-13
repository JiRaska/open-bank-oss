// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class HoneytokenFilterTest {

    private fun filter(config: String?, reg: MeterRegistry? = SimpleMeterRegistry()): HoneytokenFilter {
        val inst = mockk<Instance<MeterRegistry>>()
        if (reg == null) {
            every { inst.isResolvable } returns false
        } else {
            every { inst.isResolvable } returns true
            every { inst.get() } returns reg
        }
        return HoneytokenFilter(Optional.ofNullable(config)).apply { registryInstance = inst }
    }

    private fun request(path: String): ContainerRequestContext {
        val uriInfo = mockk<UriInfo>()
        every { uriInfo.path } returns path
        val ctx = mockk<ContainerRequestContext>(relaxed = true)
        every { ctx.uriInfo } returns uriInfo
        every { ctx.getHeaderString("X-Forwarded-For") } returns "10.0.0.9"
        return ctx
    }

    @Test
    fun `disabled when the property is absent`() {
        val ctx = request("api/v1/internal/debug")

        filter(null).filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `disabled when the property is empty`() {
        val ctx = request("api/v1/internal/debug")

        filter("").filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `a honey path hit aborts with 404 and counts the configured path`() {
        val reg = SimpleMeterRegistry()
        val ctx = request("api/v1/internal/debug")
        val aborted = slot<Response>()

        filter("/api/v1/internal/debug,/api/v1/admin/export", reg).filter(ctx)

        verify { ctx.abortWith(capture(aborted)) }
        assertThat(aborted.captured.status).isEqualTo(404)
        val counter = reg.find(SecurityTelemetry.HONEYTOKEN_HITS)
            .tags("path", "api/v1/internal/debug").counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(1.0)
    }

    @Test
    fun `leading slash in config or request is insignificant`() {
        val ctx = request("api/v1/admin/export")

        filter("api/v1/admin/export").filter(ctx)

        verify { ctx.abortWith(any()) }
    }

    @Test
    fun `a normal business path passes through untouched`() {
        val ctx = request("api/v1/payments")

        filter("/api/v1/internal/debug").filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `a prefix of a honey path is not a hit`() {
        val ctx = request("api/v1/internal")

        filter("/api/v1/internal/debug").filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `no micrometer registry still aborts with 404`() {
        val ctx = request("api/v1/internal/debug")

        filter("/api/v1/internal/debug", reg = null).filter(ctx)

        verify { ctx.abortWith(any()) }
    }

    @Test
    fun `log sanitization strips control characters and caps length`() {
        assertThat(HoneytokenFilter.sanitizeForLog("ok/path-1_2.x:@[a]")).isEqualTo("ok/path-1_2.x:@[a]")
        assertThat(HoneytokenFilter.sanitizeForLog("a\r\nb\tc\"'; DROP")).isEqualTo("abcDROP")
        assertThat(HoneytokenFilter.sanitizeForLog("x".repeat(500))).hasSize(128)
    }
}
