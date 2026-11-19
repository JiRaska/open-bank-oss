// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.mcp

import com.openbank.mcp.TestJsonWebToken
import kotlinx.coroutines.runBlocking
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
    fun `resolves agent id and consent from an agent token`(): Unit = runBlocking {
        val ctx = CallerContextResolver(
            TestJsonWebToken(
                mapOf("sub" to "agent:mcp-tpp-42", "aud" to "openbank-mcp-service", "consent_id" to "c-123"),
            ),
            customerIssuer = java.util.Optional.empty(),
        ).resolveOrNull()

        assertThat(ctx).isNotNull
        assertThat(ctx!!.agentId).isEqualTo("agent:mcp-tpp-42")
        assertThat(ctx.consentId).isEqualTo("c-123")
        // grantedAccounts is populated by the real read ports via consent-service /validate — the
        // resolver never takes account scope from the token (ADR-0195).
        assertThat(ctx.grantedAccounts).isEmpty()
    }

    @Test
    fun `returns null when no token is present (anonymous, OIDC disabled)`(): Unit = runBlocking {
        assertThat(
            CallerContextResolver(TestJsonWebToken(), customerIssuer = java.util.Optional.empty()).resolveOrNull(),
        ).isNull()
    }

    @Test
    fun `returns null when the subject is not an agent principal`(): Unit = runBlocking {
        val ctx = CallerContextResolver(
            TestJsonWebToken(mapOf("sub" to "service-account-openbank-services")),
            customerIssuer = java.util.Optional.empty(),
        ).resolveOrNull()
        assertThat(ctx).isNull()
    }

    @Test
    fun `fails closed when an agent token carries no consent_id`() {
        assertThatThrownBy {
            runBlocking {
                CallerContextResolver(
                    TestJsonWebToken(
                        mapOf(
                            "sub" to "agent:mcp-tpp-42",
                            "aud" to "openbank-mcp-service",
                        ),
                    ),
                    customerIssuer = java.util.Optional.empty(),
                ).resolveOrNull()
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("consent_id")
    }

    @Test
    fun `fails closed when consent_id is blank`() {
        assertThatThrownBy {
            runBlocking {
                CallerContextResolver(
                    TestJsonWebToken(
                        mapOf(
                            "sub" to "agent:mcp-tpp-42",
                            "aud" to "openbank-mcp-service",
                            "consent_id" to "  ",
                        ),
                    ),
                    customerIssuer = java.util.Optional.empty(),
                ).resolveOrNull()
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    // #16622: RFC 8707 — a valid agent token issued for another resource is never accepted.
    @Test
    fun `returns null for an agent token issued for another audience`(): Unit = runBlocking {
        val jwt = TestJsonWebToken(
            mapOf(
                "sub" to "agent:mcp-tpp-42",
                "aud" to "openbank-admin-ui",
                "consent_id" to "c-1",
            ),
        )
        assertThat(CallerContextResolver(jwt, customerIssuer = java.util.Optional.empty()).resolveOrNull()).isNull()
        assertThat(
            CallerContextResolver(jwt, customerIssuer = java.util.Optional.empty()).presentsForeignAudience(),
        ).isTrue()
    }

    @Test
    fun `accepts the audience when it is one entry of an array`(): Unit = runBlocking {
        val jwt = TestJsonWebToken(
            mapOf(
                "sub" to "agent:mcp-tpp-42",
                "aud" to listOf("account", "openbank-mcp-service"),
                "consent_id" to "c-1",
            ),
        )
        assertThat(CallerContextResolver(jwt, customerIssuer = java.util.Optional.empty()).resolveOrNull()).isNotNull
        assertThat(
            CallerContextResolver(jwt, customerIssuer = java.util.Optional.empty()).presentsForeignAudience(),
        ).isFalse()
    }

    @Test
    fun `an anonymous caller is not a foreign audience`() {
        assertThat(
            CallerContextResolver(
                TestJsonWebToken(),
                customerIssuer = java.util.Optional.empty(),
            ).presentsForeignAudience(),
        ).isFalse()
    }

    @Test
    fun `a customers-realm token is never resolved as an agent or OBO caller, whatever its claims`(): Unit =
        runBlocking {
            val jwt = TestJsonWebToken(
                mapOf(
                    "iss" to "https://kc.test.example/realms/openbank-customers",
                    "sub" to "agent:mcp-tpp-42",
                    "aud" to "openbank-mcp-service",
                    "consent_id" to "c-123",
                ),
            )
            val resolver = CallerContextResolver(
                jwt,
                oboEnabled = true,
                customerIssuer = java.util.Optional.of("https://kc.test.example/realms/openbank-customers"),
            )
            assertThat(resolver.resolveOrNull()).isNull()
            // Control: the same claims from the staff issuer still resolve — the issuer is what decides.
            val staff = TestJsonWebToken(
                jwt.claimNames.associateWith { jwt.getClaim<Any?>(it) } +
                    ("iss" to "https://kc.test.example/realms/openbank"),
            )
            assertThat(
                CallerContextResolver(
                    staff,
                    customerIssuer = java.util.Optional.of("https://kc.test.example/realms/openbank-customers"),
                )
                    .resolveOrNull(),
            ).isNotNull()
        }
}
