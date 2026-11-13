// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.security

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.security.Principal

class MonitoringCallerIdentityTest {

    private fun token(azp: String?, username: String?, subject: String? = "sub-1"): SecurityIdentity {
        val jwt = mockk<JsonWebToken>()
        every { jwt.subject } returns subject
        every { jwt.getClaim<Any?>("azp") } returns azp
        every { jwt.getClaim<Any?>("preferred_username") } returns username
        return mockk { every { principal } returns jwt }
    }

    private fun refused(identity: SecurityIdentity?) =
        assertThatThrownBy { MonitoringCallerIdentity.ownerOf(identity) }.isInstanceOf(ForbiddenException::class.java)

    @Test
    fun `the owner is the client a service-account token was issued to`() {
        assertThat(MonitoringCallerIdentity.ownerOf(token("openbank-kyb", "service-account-openbank-kyb")))
            .isEqualTo("openbank-kyb")
    }

    @Test
    fun `a username naming another client's service account does not make the caller that client`() {
        refused(token("openbank-lending", "service-account-openbank-kyb"))
    }

    @Test
    fun `a person logged in through a client is not a service`() {
        refused(token("openbank-admin-ui", "analyst"))
    }

    @Test
    fun `a token without a subject or without the client it was issued to is refused`() {
        refused(token("openbank-kyb", "service-account-openbank-kyb", subject = null))
        refused(token(null, "service-account-openbank-kyb"))
    }

    @Test
    fun `the shared fleet client is refused`() {
        refused(token("openbank-services", "service-account-openbank-services"))
    }

    @Test
    fun `a principal that is not a token, and no identity at all, are refused`() {
        val named = mockk<Principal> { every { name } returns "service-account-openbank-kyb" }
        refused(mockk { every { principal } returns named })
        refused(null)
    }
}
