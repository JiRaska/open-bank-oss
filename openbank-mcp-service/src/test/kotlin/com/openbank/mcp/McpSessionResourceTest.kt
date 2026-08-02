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

    /**
     * [operator] is the display name (`preferred_username`); [sub] is what the session is keyed
     * on. They are separate parameters on purpose — keying on the display name is #3182.
     */
    private fun resource(
        operator: String,
        roles: Set<String>,
        sub: String = "11111111-1111-1111-1111-111111111111",
        repository: AgentSessionRepository = repo,
    ) = McpSessionResource(repository, clock, 15).apply {
        identity = QuarkusSecurityIdentity.builder()
            .setPrincipal { operator }
            .apply { roles.forEach { addRole(it) } }
            .build() as SecurityIdentity
        jwt = TestJsonWebToken(mapOf("sub" to sub, "preferred_username" to operator))
    }

    @Test
    fun `issuance bounds the ceiling to the caller's own roles`(): Unit = runBlocking {
        val res = resource("jane.operator", setOf("ROLE_OPERATOR"))
            .create(CreateSessionRequest(roleCeiling = listOf("ROLE_OPERATOR", "ROLE_ADMIN")))
        assertThat(res.status).isEqualTo(201)
        assertThat(saved.single().roleCeiling).isEqualTo("[\"ROLE_OPERATOR\"]")
        assertThat(saved.single().subject).isEqualTo("11111111-1111-1111-1111-111111111111")
    }

    @Test
    fun `a ceiling entirely outside the caller's roles is refused`(): Unit = runBlocking {
        val res = resource("jane.operator", setOf("ROLE_OPERATOR"))
            .create(CreateSessionRequest(roleCeiling = listOf("ROLE_ADMIN")))
        assertThat(res.status).isEqualTo(403)
        assertThat(saved).isEmpty()
    }

    @Test
    fun `another operator cannot read or revoke a session that is not theirs`(): Unit = runBlocking {
        val foreign = AgentSessionEntity().also {
            it.id = java.util.UUID.randomUUID()
            it.subject = "someone.else"
            it.roleCeiling = "[\"ROLE_OPERATOR\"]"
            it.clientId = "admin-ui"
            it.createdAt = Instant.now(clock)
            it.expiresAt = Instant.now(clock).plusSeconds(900)
        }
        val repoWithForeign = object : AgentSessionRepository() {
            override suspend fun findById(id: java.util.UUID) = if (id == foreign.id) foreign else null
        }
        val outsider = resource("jane.operator", setOf("ROLE_OPERATOR"), repository = repoWithForeign)
        assertThat(outsider.status(foreign.id).status).isEqualTo(403)
        assertThat(outsider.revoke(foreign.id).status).isEqualTo(403)
    }

    @Test
    fun `an admin can read and revoke any session`(): Unit = runBlocking {
        val foreign = AgentSessionEntity().also {
            it.id = java.util.UUID.randomUUID()
            it.subject = "someone.else"
            it.roleCeiling = "[\"ROLE_OPERATOR\"]"
            it.clientId = "admin-ui"
            it.createdAt = Instant.now(clock)
            it.expiresAt = Instant.now(clock).plusSeconds(900)
        }
        val repoWithForeign = object : AgentSessionRepository() {
            override suspend fun findById(id: java.util.UUID) = if (id == foreign.id) foreign else null
            override suspend fun revoke(id: java.util.UUID, asOf: Instant) = id == foreign.id
        }
        val admin = McpSessionResource(repoWithForeign, clock, 15).apply {
            identity = QuarkusSecurityIdentity.builder()
                .setPrincipal { "boss.admin" }
                .addRole("ROLE_ADMIN")
                .build() as SecurityIdentity
        }
        assertThat(admin.status(foreign.id).status).isEqualTo(200)
        assertThat(admin.revoke(foreign.id).status).isEqualTo(204)
    }

    @Test
    fun `a Keycloak rename does not strand the caller's own session`(): Unit = runBlocking {
        // The session is issued before the rename...
        assertThat(
            resource("jane.operator", setOf("ROLE_OPERATOR")).create(
                CreateSessionRequest(roleCeiling = listOf("ROLE_OPERATOR")),
            ).status,
        ).isEqualTo(201)
        val session = saved.single()

        // ...and the admin then changes preferred_username. `sub` is unchanged, because Keycloak
        // guarantees it is. Keyed on the display name, `owns()` would now say "not your session"
        // and the OBO resolver would fall through to anonymous — a fail-closed
        // "Authorization unavailable" against a row that still reads live (#3182).
        val repoWithSession = object : AgentSessionRepository() {
            override suspend fun findById(id: java.util.UUID) = if (id == session.id) session else null
        }
        val renamed = resource("jane.smith", setOf("ROLE_OPERATOR"), repository = repoWithSession)

        assertThat(renamed.status(session.id).status)
            .describedAs("the same human, one Keycloak rename later, must still own their session")
            .isEqualTo(200)
    }

    @Test
    fun `a different human with the same display name does not own the session`(): Unit = runBlocking {
        // The converse, which is what keying on an immutable id actually buys: display names are
        // not unique over time, so matching on one could hand a session to the wrong person.
        assertThat(
            resource("jane.operator", setOf("ROLE_OPERATOR")).create(
                CreateSessionRequest(roleCeiling = listOf("ROLE_OPERATOR")),
            ).status,
        ).isEqualTo(201)
        val session = saved.single()
        val repoWithSession = object : AgentSessionRepository() {
            override suspend fun findById(id: java.util.UUID) = if (id == session.id) session else null
        }
        val impostor = resource(
            "jane.operator",
            setOf("ROLE_OPERATOR"),
            sub = "22222222-2222-2222-2222-222222222222",
            repository = repoWithSession,
        )

        assertThat(impostor.status(session.id).status).isEqualTo(403)
    }
}
