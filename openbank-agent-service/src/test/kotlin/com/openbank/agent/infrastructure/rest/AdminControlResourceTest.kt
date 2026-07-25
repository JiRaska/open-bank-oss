// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.port.`in`.KillSwitchControlUseCase
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal

class AdminControlResourceTest {

    private fun resource(operator: String?): Pair<AdminControlResource, KillSwitchControlUseCase> {
        val ks = mockk<KillSwitchControlUseCase>()
        justRun { ks.halt(any(), any(), any()) }
        justRun { ks.resume(any(), any()) }
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns operator?.let { Principal { it } }
        val res = AdminControlResource().also {
            it.control = ks
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
