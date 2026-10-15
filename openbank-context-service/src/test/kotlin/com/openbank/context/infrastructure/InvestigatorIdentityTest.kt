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
}
