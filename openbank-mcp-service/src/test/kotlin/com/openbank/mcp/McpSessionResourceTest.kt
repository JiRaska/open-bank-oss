// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp

import com.openbank.mcp.infrastructure.persistence.AgentSessionEntity
import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import com.openbank.mcp.infrastructure.rest.CreateSessionRequest
import com.openbank.mcp.infrastructure.rest.McpSessionResource
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Plain-unit coverage of the session issuance contract (ADR-0224 D2): the role ceiling is
 * intersected with the caller's own roles server-side, and an empty intersection is refused —
 * a session can never exceed its owner's authority.
 */
class McpSessionResourceTest {

    private val saved = mutableListOf<AgentSessionEntity>()

    private val repo = object : AgentSessionRepository() {
        override suspend fun save(session: AgentSessionEntity) {
            saved += session
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-07-31T10:00:00Z"), ZoneOffset.UTC)

    private fun resource(operator: String, roles: Set<String>) = McpSessionResource(repo, clock, 15).apply {
        identity = QuarkusSecurityIdentity.builder()
            .setPrincipal { operator }
            .apply { roles.forEach { addRole(it) } }
            .build() as SecurityIdentity
    }

    @Test
    fun `issuance bounds the ceiling to the caller's own roles`(): Unit = runBlocking {
        val res = resource("jane.operator", setOf("ROLE_OPERATOR"))
            .create(CreateSessionRequest(roleCeiling = listOf("ROLE_OPERATOR", "ROLE_ADMIN")))
        assertThat(res.status).isEqualTo(201)
        assertThat(saved.single().roleCeiling).isEqualTo("[\"ROLE_OPERATOR\"]")
        assertThat(saved.single().subject).isEqualTo("jane.operator")
    }

    @Test
    fun `a ceiling entirely outside the caller's roles is refused`(): Unit = runBlocking {
        val res = resource("jane.operator", setOf("ROLE_OPERATOR"))
            .create(CreateSessionRequest(roleCeiling = listOf("ROLE_ADMIN")))
        assertThat(res.status).isEqualTo(403)
        assertThat(saved).isEmpty()
    }
}
