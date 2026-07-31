// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.mcp.infrastructure.persistence.AgentSessionEntity
import com.openbank.mcp.infrastructure.persistence.AgentSessionRepository
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Staff agent-session lifecycle (ADR-0224 D2). Issued to the authenticated operator themselves,
 * bounding the session to a role ceiling they actually hold plus a client binding and a TTL.
 * Revocation must take effect immediately — the OBO resolver validates sessions live against
 * this store on every call.
 */
@Path("/api/v1/mcp/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "MCP Sessions")
class McpSessionResource(
    private val sessions: AgentSessionRepository,
    private val clock: Clock,
    @ConfigProperty(name = "mcp.sessions.ttl-minutes", defaultValue = "15") private val ttlMinutes: Long,
) {

    @Inject
    lateinit var identity: SecurityIdentity

    private val log = Logger.getLogger(McpSessionResource::class.java)

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "mcp.session.create", resource = "")
    @Operation(summary = "Issue a staff OBO agent session, role ceiling = requested ∩ held roles")
    suspend fun create(request: CreateSessionRequest): Response {
        val held = identity.roles.filter { it.startsWith("ROLE_") }.toSet()
        val ceiling = request.roleCeiling.filter { it in held }
        if (ceiling.isEmpty()) {
            return Response.status(Response.Status.FORBIDDEN).entity(
                mapOf(
                    "error" to "role ceiling must be a non-empty subset of your own roles",
                ),
            ).build()
        }
        val now = Instant.now(clock)
        val session = AgentSessionEntity().also {
            it.id = Ids.newId()
            it.subject = identity.principal.name
            it.roleCeiling = ceiling.joinToString(",", prefix = "[", postfix = "]") { r -> "\"$r\"" }
            it.clientId = request.clientId
            it.purpose = request.purpose
            it.createdAt = now
            it.expiresAt = now.plus(ttlMinutes, ChronoUnit.MINUTES)
        }
        sessions.save(session)
        return Response.status(Response.Status.CREATED).entity(session.toResponse()).build()
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "mcp.session.read", resource = "#id")
    @Operation(summary = "Session status (only the owner, or an admin)")
    suspend fun status(@PathParam("id") id: UUID): Response {
        val session = sessions.findById(id) ?: throw NotFoundException("no session $id")
        if (!owns(session)) return forbidden()
        return Response.ok(session.toResponse()).build()
    }

    @PATCH
    @Path("/{id}/bind")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "mcp.session.bind", resource = "#id")
    @Operation(
        summary = "Bind a session to the jti of the token minted for it (ADR-0224 D2) — " +
            "called by the BFF right after a successful Keycloak token exchange",
    )
    suspend fun bind(@PathParam("id") id: UUID, request: BindSessionRequest): Response {
        val session = sessions.findById(id) ?: throw NotFoundException("no session $id")
        if (!owns(session)) return forbidden()
        if (session.jti != null) {
            return Response.status(Response.Status.CONFLICT)
                .entity(mapOf("error" to "session already bound"))
                .build()
        }
        session.jti = request.jti
        return try {
            sessions.merge(session)
            Response.ok(session.toResponse()).build()
        } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
            // The unique jti index rejects two sessions racing for the same token — a clean 409,
            // not an unhandled 500.
            log.warnf("session bind rejected for %s: %s", id, ex.message)
            Response.status(Response.Status.CONFLICT).entity(mapOf("error" to "jti already bound")).build()
        }
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "mcp.session.revoke", resource = "#id")
    @Operation(summary = "Revoke a session immediately (only the owner, or an admin)")
    suspend fun revoke(@PathParam("id") id: UUID): Response {
        val session = sessions.findById(id)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "no active session $id"))
                .build()
        if (!owns(session)) return forbidden()
        if (!sessions.revoke(id, Instant.now(clock))) {
            return Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "no active session $id")).build()
        }
        return Response.noContent().build()
    }

    // Broken-access-control guard: the role grant admits every operator, but a session row is
    // its owner's alone — reading or revoking another operator's session needs ROLE_ADMIN.
    private fun owns(session: AgentSessionEntity): Boolean =
        identity.roles.contains("ROLE_ADMIN") || session.subject == identity.principal.name

    private fun forbidden(): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to "not your session")).build()
}

data class CreateSessionRequest(
    val clientId: String = "admin-ui",
    val roleCeiling: List<String> = emptyList(),
    val purpose: String? = null,
)

data class BindSessionRequest(val jti: String)

fun AgentSessionEntity.toResponse() = mapOf(
    "id" to id,
    "subject" to subject,
    "roleCeiling" to roleCeiling,
    "clientId" to clientId,
    "purpose" to purpose,
    "createdAt" to createdAt.toString(),
    "expiresAt" to expiresAt.toString(),
    "revoked" to (revokedAt != null),
)
