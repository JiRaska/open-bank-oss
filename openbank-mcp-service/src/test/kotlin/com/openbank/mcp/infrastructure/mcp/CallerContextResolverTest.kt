// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.mcp.TestJsonWebToken
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Caller authentication (ADR-0195): the resolver turns a validated agent token into a
 * [com.openbank.mcp.application.port.out.ConsentContext], or `null` when no agent token is present
 * so the endpoint keeps its phase-1 placeholder. Plain-unit — no Quarkus, a POJO JWT.
 */
class CallerContextResolverTest {

    @Test
    fun `resolves agent id and consent from an agent token`() {
        val ctx = CallerContextResolver(
            TestJsonWebToken(mapOf("sub" to "agent:mcp-tpp-42", "consent_id" to "c-123")),
        ).resolveOrNull()

        assertThat(ctx).isNotNull
        assertThat(ctx!!.agentId).isEqualTo("agent:mcp-tpp-42")
        assertThat(ctx.consentId).isEqualTo("c-123")
        // grantedAccounts is populated by the real read ports via consent-service /validate — the
        // resolver never takes account scope from the token (ADR-0195).
        assertThat(ctx.grantedAccounts).isEmpty()
    }

    @Test
    fun `returns null when no token is present (anonymous, OIDC disabled)`() {
        assertThat(CallerContextResolver(TestJsonWebToken()).resolveOrNull()).isNull()
    }

    @Test
    fun `returns null when the subject is not an agent principal`() {
        val ctx = CallerContextResolver(
            TestJsonWebToken(mapOf("sub" to "service-account-openbank-services")),
        ).resolveOrNull()
        assertThat(ctx).isNull()
    }

    @Test
    fun `fails closed when an agent token carries no consent_id`() {
        assertThatThrownBy {
            CallerContextResolver(TestJsonWebToken(mapOf("sub" to "agent:mcp-tpp-42"))).resolveOrNull()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("consent_id")
    }

    @Test
    fun `fails closed when consent_id is blank`() {
        assertThatThrownBy {
            CallerContextResolver(
                TestJsonWebToken(mapOf("sub" to "agent:mcp-tpp-42", "consent_id" to "  ")),
            ).resolveOrNull()
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
