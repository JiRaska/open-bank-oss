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

    // #2938: with a real OIDC identity, `principal.name` is the name claim and `sub` is a UUID.
    // The row must hold `sub` — CallerContextResolver validates an OBO token against exactly that,
    // so a row keyed by the name claim can never match and every staff call fell through to
    // anonymous. Asserting on `saved.subject` alone is not enough: the fixture has to make the two
    // claims DIFFER, or the old code passes too.
    private fun jwtResource(sub: String, username: String, roles: Set<String>) =
        McpSessionResource(repo, clock, 15).apply {
            val token = TestJsonWebToken(mapOf("sub" to sub, "preferred_username" to username))
            identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(token)
                .apply { roles.forEach { addRole(it) } }
                .build() as SecurityIdentity
        }

    @Test
    fun `a session issued from a JWT identity is keyed by sub, not by the name claim`(): Unit = runBlocking {
        val res = jwtResource(OPERATOR_SUB, "admin@openbank.local", setOf("ROLE_OPERATOR"))
            .create(CreateSessionRequest(roleCeiling = listOf("ROLE_OPERATOR")))
        assertThat(res.status).isEqualTo(201)
        assertThat(saved.single().subject).isEqualTo(OPERATOR_SUB)
        assertThat(saved.single().subject).isNotEqualTo("admin@openbank.local")
    }

    @Test
    fun `ownership of a sub-keyed session is recognised, and a name-keyed row is not theirs`(): Unit = runBlocking {
        val mine = session(subject = OPERATOR_SUB)
        val legacy = session(subject = "admin@openbank.local")
        val repoWithBoth = object : AgentSessionRepository() {
            override suspend fun findById(id: java.util.UUID) = when (id) {
                mine.id -> mine
                legacy.id -> legacy
                else -> null
            }
        }
        val caller = McpSessionResource(repoWithBoth, clock, 15).apply {
            identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(
                    TestJsonWebToken(mapOf("sub" to OPERATOR_SUB, "preferred_username" to "admin@openbank.local")),
                )
                .addRole("ROLE_OPERATOR")
                .build() as SecurityIdentity
        }
        assertThat(caller.status(mine.id).status).isEqualTo(200)
        // A row written by the pre-#2938 code holds the name claim: it is not this caller's row.
        assertThat(caller.status(legacy.id).status).isEqualTo(403)
    }

    private fun session(subject: String) = AgentSessionEntity().also {
        it.id = java.util.UUID.randomUUID()
        it.subject = subject
        it.roleCeiling = "[\"ROLE_OPERATOR\"]"
        it.clientId = "admin-ui"
        it.createdAt = Instant.now(clock)
        it.expiresAt = Instant.now(clock).plusSeconds(900)
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
        val outsider = McpSessionResource(repoWithForeign, clock, 15).apply {
            identity = QuarkusSecurityIdentity.builder()
                .setPrincipal { "jane.operator" }
                .addRole("ROLE_OPERATOR")
                .build() as SecurityIdentity
        }
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

    private companion object {
        /** A realm `sub` is a UUID; the name claim is an email. The difference IS the test. */
        const val OPERATOR_SUB = "3a046823-5d47-4de1-9f3f-b1b2a953d2cc"
    }
}
