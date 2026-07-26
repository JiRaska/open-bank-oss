// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.SecurityContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.UUID

class SecurityContextExtensionsTest {

    private fun contextWith(principalName: String?): SecurityContext {
        val ctx = mockk<SecurityContext>()
        val principal = principalName?.let { name ->
            mockk<Principal>().also { every { it.name } returns name }
        }
        every { ctx.userPrincipal } returns principal
        every { ctx.isUserInRole(any()) } returns false
        return ctx
    }

    @Test
    fun `currentUserId parses a valid UUID from the principal name`() {
        val id = UUID.randomUUID()
        assertThat(contextWith(id.toString()).currentUserId).isEqualTo(id)
    }

    @Test
    fun `currentUserId is null when the principal name is not a UUID`() {
        assertThat(contextWith("not-a-uuid").currentUserId).isNull()
    }

    @Test
    fun `currentUserId is null when there is no principal`() {
        assertThat(contextWith(null).currentUserId).isNull()
    }

    @Test
    fun `actorName returns the principal name`() {
        assertThat(contextWith("alice").actorName).isEqualTo("alice")
    }

    @Test
    fun `actorName falls back to anonymous when there is no principal`() {
        assertThat(contextWith(null).actorName).isEqualTo("anonymous")
    }

    @Test
    fun `actorType returns the most privileged role when several are held`() {
        val ctx = contextWith("alice")
        every { ctx.isUserInRole(Roles.ADMIN) } returns true
        every { ctx.isUserInRole(Roles.COMPLIANCE) } returns true

        // ADMIN precedes COMPLIANCE in Roles.ALL declaration order.
        assertThat(ctx.actorType).isEqualTo(Roles.ADMIN)
    }

    @Test
    fun `actorType returns the single held role`() {
        val ctx = contextWith("svc")
        every { ctx.isUserInRole(Roles.API) } returns true

        assertThat(ctx.actorType).isEqualTo(Roles.API)
    }

    @Test
    fun `actorType falls back to anonymous when no role is held`() {
        assertThat(contextWith("alice").actorType).isEqualTo("anonymous")
    }

    @Test
    fun `requireAnyRole passes when one required role is present`() {
        val ctx = contextWith("alice")
        every { ctx.isUserInRole(Roles.PAYMENTS) } returns true

        ctx.requireAnyRole(Roles.ADMIN, Roles.PAYMENTS)
    }

    @Test
    fun `requireAnyRole throws SecurityException when no required role is present`() {
        val ctx = contextWith("alice")

        assertThatThrownBy { ctx.requireAnyRole(Roles.ADMIN, Roles.PAYMENTS) }
            .isInstanceOf(SecurityException::class.java)
    }
}
