// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.AuthorizationRequest
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HostedOAuthAdmissionTest {
    @Test
    fun `vendor policy is disabled by default and keeps separate bank identities`() {
        assertThat(HostedOAuthAdmission.admit(request())).isNull()
        val chatgpt = HostedOAuthAdmission.admit(request(), setOf(Vendor.CHATGPT))!!
        val claude = HostedOAuthAdmission.admit(
            request(client = Vendor.CLAUDE.clientId, redirect = Vendor.CLAUDE.callback),
            setOf(Vendor.CLAUDE),
        )!!
        assertThat(chatgpt.vendor.bankClientId).isNotEqualTo(claude.vendor.bankClientId)
    }

    @Test
    fun `authorization pins vendor callback resource scope and PKCE`() {
        val invalidRequests = listOf(
            request(client = Vendor.CLAUDE.clientId),
            request(redirect = Vendor.CHATGPT.callback + "?extra=1"),
            request(type = "token"),
            request(method = "plain"),
            request(challenge = CHALLENGE.dropLast(1) + "N"),
            request(state = ""),
            request(state = "a\nb"),
            request(resource = HostedOAuthAdmission.RESOURCE + "/"),
            request(scope = "openid offline_access"),
        )
        assertThat(invalidRequests).allMatch { HostedOAuthAdmission.admit(it, Vendor.entries.toSet()) == null }
    }

    @Test
    fun `token exchange matches only the same authenticated vendor and verifier`() {
        val code = HostedOAuthAdmission.admit(request(), setOf(Vendor.CHATGPT))!!
        assertThat(exchange(code)).isTrue()
        assertThat(exchange(code, vendor = Vendor.CLAUDE)).isFalse()
        assertThat(exchange(code, redirect = Vendor.CLAUDE.callback)).isFalse()
        assertThat(exchange(code, resource = null)).isFalse()
        assertThat(exchange(code, verifier = VERIFIER.reversed())).isFalse()
    }

    private fun request(
        client: String = Vendor.CHATGPT.clientId,
        redirect: String = Vendor.CHATGPT.callback,
        type: String = "code",
        challenge: String = CHALLENGE,
        method: String = "S256",
        state: String = "opaque-state",
        scope: String = HostedOAuthAdmission.OUTER_SCOPE,
        resource: String = HostedOAuthAdmission.RESOURCE,
    ) = AuthorizationRequest(client, redirect, type, challenge, method, state, scope, resource)

    private fun exchange(
        authorization: HostedOAuthAdmission.Authorization,
        vendor: Vendor = Vendor.CHATGPT,
        redirect: String = Vendor.CHATGPT.callback,
        resource: String? = HostedOAuthAdmission.RESOURCE,
        verifier: String = VERIFIER,
    ) = HostedOAuthAdmission.matchesExchange(authorization, vendor, redirect, resource, verifier)

    private companion object {
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }
}
