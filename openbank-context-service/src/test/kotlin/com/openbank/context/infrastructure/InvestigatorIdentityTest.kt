// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.security.Principal

class InvestigatorIdentityTest {
    private fun identity(client: String, username: String, machine: String? = null) =
        QuarkusSecurityIdentity.builder().setPrincipal(
            mockk<JsonWebToken> {
                every { name } returns username
                every { subject } returns "synthetic-subject"
                every { claimNames } returns setOf("azp", "preferred_username") +
                    (if (machine != null) setOf("client_id") else emptySet())
                every { getClaim<Any?>(any<String>()) } answers {
                    mapOf("azp" to client, "preferred_username" to username, "client_id" to machine)[firstArg<String>()]
                }
            },
        ).addRoles(setOf("ROLE_COMPLIANCE")).build()

    @Test
    fun `verified human classification and attribution survive the custom boundary`() {
        val actor = identity("openbank-admin-ui", "reviewer-fixture").investigator()
        assertThat(actor.id).isEqualTo("reviewer-fixture")
        assertThat(actor.roles).containsExactly("ROLE_COMPLIANCE")
        assertThat(actor.serviceAccount).isFalse()
        assertThat(actor.clientId).isNull()
    }

    @Test
    fun `machine client proof survives without granting a human classification`() {
        val actor = identity("fixture", "service-account-fixture", "fixture").investigator()
        assertThat(actor.serviceAccount).isTrue()
        assertThat(actor.clientId).isEqualTo("fixture")
    }

    @Test
    fun `renamed machine keeps its class but receives no named-client proof`() {
        val actor = identity("fixture", "renamed-fixture", "fixture").investigator()
        assertThat(actor.serviceAccount).isTrue()
        assertThat(actor.clientId).isNull()
    }

    @Test
    fun `a non-JWT staff-looking principal is not promoted to verified human`() {
        val actor = QuarkusSecurityIdentity.builder().setPrincipal(Principal { "reviewer-fixture" })
            .addRoles(setOf("ROLE_COMPLIANCE")).build().investigator()
        assertThat(actor.serviceAccount).isTrue()
        assertThat(actor.clientId).isNull()
    }

    @Test
    fun `unreadable claims remain fail closed`() {
        val actor = QuarkusSecurityIdentity.builder().setPrincipal(
            mockk<JsonWebToken> {
                every { name } returns "reviewer-fixture"
                every { subject } returns "synthetic-subject"
                every { getClaim<Any?>(any<String>()) } throws IllegalStateException("unreadable claims")
            },
        ).build().investigator()
        assertThat(actor.serviceAccount).isTrue()
        assertThat(actor.clientId).isNull()
    }

    private fun fromClaims(claims: Map<String, Any?>, subject: String? = "synthetic-subject") =
        QuarkusSecurityIdentity.builder().setPrincipal(
            mockk<JsonWebToken> {
                every { name } returns "reviewer-fixture"
                every { this@mockk.subject } returns subject
                every { claimNames } returns claims.keys
                every { getClaim<Any?>(any<String>()) } answers { claims[firstArg<String>()] }
            },
        ).addRoles(setOf("ROLE_COMPLIANCE")).build().investigator()

    @Test
    fun `all declared interactive staff clients preserve human classification`() {
        listOf("openbank-admin-ui", "openbank-ops-cli", "admin-cli").forEach { client ->
            assertThat(fromClaims(mapOf("azp" to client, "preferred_username" to "reviewer-fixture")).serviceAccount)
                .describedAs(client).isFalse()
        }
    }

    @Test
    fun `an unfamiliar client cannot supply human evidence through a renamed username`() {
        val actor = fromClaims(mapOf("azp" to "openbank-services", "preferred_username" to "reviewer-fixture"))
        assertThat(actor.serviceAccount).isTrue()
    }

    @Test
    fun `machine note presence denies null blank and malformed values on an interactive client`() {
        listOf("client_id", "clientId").forEach { note ->
            listOf(null, "", 123, "fixture").forEach { value ->
                val claims = mapOf(
                    "azp" to "openbank-admin-ui",
                    "preferred_username" to "reviewer-fixture",
                    note to value,
                )
                assertThat(fromClaims(claims).serviceAccount).describedAs("%s=%s", note, value).isTrue()
            }
        }
    }

    @Test
    fun `missing client subject or valid human username is not human evidence`() {
        val valid = mapOf<String, Any?>("azp" to "openbank-admin-ui", "preferred_username" to "reviewer-fixture")
        listOf(null, "").forEach { subject -> assertThat(fromClaims(valid, subject).serviceAccount).isTrue() }
        listOf(null, "", 123, "service-account-fixture").forEach { username ->
            assertThat(fromClaims(valid + ("preferred_username" to username)).serviceAccount).isTrue()
        }
        assertThat(fromClaims(valid - "azp").serviceAccount).isTrue()
    }
}
