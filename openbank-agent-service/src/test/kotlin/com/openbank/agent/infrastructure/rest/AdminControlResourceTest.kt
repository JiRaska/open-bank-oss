// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.KillSwitchService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal

class AdminControlResourceTest {

    private fun resource(operator: String?): Pair<AdminControlResource, KillSwitchService> {
        val ks = mockk<KillSwitchService>()
        justRun { ks.halt(any(), any(), any()) }
        justRun { ks.resume(any(), any()) }
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns operator?.let { Principal { it } }
        val res = AdminControlResource().also {
            it.killSwitch = ks
            it.identity = identity
        }
        return res to ks
    }

    @Test
    fun `halt records the authenticated operator as setBy, never the request body`() {
        val (res, ks) = resource("alice")
        res.halt(AdminControlResource.HaltRequest(scope = "ui-assistant", reason = "maintenance"))
        // setBy is the OIDC subject ("alice"), not anything the caller could supply.
        verify(exactly = 1) { ks.halt("ui-assistant", "maintenance", "alice") }
    }

    @Test
    fun `resume records the authenticated operator`() {
        val (res, ks) = resource("bob")
        res.resume(AdminControlResource.ResumeRequest(scope = "*"))
        verify(exactly = 1) { ks.resume("*", "bob") }
    }

    @Test
    fun `missing principal falls back to 'unknown' rather than failing`() {
        val (res, ks) = resource(null)
        res.halt(AdminControlResource.HaltRequest(scope = "ui-assistant", reason = "x"))
        verify(exactly = 1) { ks.halt("ui-assistant", "x", "unknown") }
    }

    @Test
    fun `blank scope is rejected with 400`() {
        val (res, _) = resource("alice")
        assertThat(res.halt(AdminControlResource.HaltRequest(scope = " ", reason = "x")).status).isEqualTo(400)
    }
}
