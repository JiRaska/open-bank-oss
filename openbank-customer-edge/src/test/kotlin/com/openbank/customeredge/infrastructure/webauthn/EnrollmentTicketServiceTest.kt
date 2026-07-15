// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnrollmentTicketServiceTest {

    private fun service(secret: String = "test-secret") = EnrollmentTicketService().apply { this.secret = secret }

    @Test
    fun `a freshly issued ticket verifies back to the same partyId`() {
        val svc = service()
        val ticket = svc.issue("party-123")
        assertThat(svc.verify(ticket)).isEqualTo("party-123")
    }

    @Test
    fun `a ticket signed with a different secret does not verify`() {
        val ticket = service("secret-a").issue("party-123")
        assertThat(service("secret-b").verify(ticket)).isNull()
    }

    @Test
    fun `tampering with the partyId invalidates the MAC`() {
        val svc = service()
        val ticket = svc.issue("party-123")
        val (_, expiresAt, mac) = ticket.split(".")
        val tamperedPartyId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("party-456".toByteArray())
        assertThat(svc.verify("$tamperedPartyId.$expiresAt.$mac")).isNull()
    }

    @Test
    fun `tampering with the expiry invalidates the MAC`() {
        val svc = service()
        val ticket = svc.issue("party-123")
        val (partyIdB64, expiresAt, mac) = ticket.split(".")
        val laterExpiry = expiresAt.toLong() + 3600
        assertThat(svc.verify("$partyIdB64.$laterExpiry.$mac")).isNull()
    }

    @Test
    fun `an expired ticket does not verify`() {
        val svc = service()
        val partyIdB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("party-123".toByteArray())
        val pastExpiry = (System.currentTimeMillis() / 1000L) - 1
        // Hand-craft a ticket with a MAC computed the same way issue() would, but for an
        // already-past expiry — issue() itself always issues a future one.
        val mac = run {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec("test-secret".toByteArray(), "HmacSHA256"))
            mac.doFinal("party-123.$pastExpiry".toByteArray()).joinToString("") { "%02x".format(it) }
        }
        assertThat(svc.verify("$partyIdB64.$pastExpiry.$mac")).isNull()
    }

    @Test
    fun `malformed tickets do not verify`() {
        val svc = service()
        assertThat(svc.verify("not-a-ticket")).isNull()
        assertThat(svc.verify("a.b")).isNull()
        assertThat(svc.verify("")).isNull()
    }

    @Test
    fun `an unconfigured secret rejects everything`() {
        val svc = service(secret = "")
        val ticket = service("some-secret").issue("party-123")
        assertThat(svc.verify(ticket)).isNull()
    }
}
