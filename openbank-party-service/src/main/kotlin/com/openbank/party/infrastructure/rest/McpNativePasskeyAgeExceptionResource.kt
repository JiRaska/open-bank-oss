// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.OffsetDateTime
import java.util.UUID

@Path("/api/v1/parties/{partyId}/mcp-native-passkey-age-exception")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class McpNativePasskeyAgeExceptionResource(
    private val identity: SecurityIdentity,
    private val pool: Pool,
    @ConfigProperty(name = "openbank.party.sandbox-mcp-native-age-exceptions", defaultValue = "false")
    private val enabled: Boolean,
) {
    data class Change(val minimumAgeMinutes: Int?, val expiresAt: OffsetDateTime?, val reason: String?)
    data class Effective(val minimumAgeMinutes: Int, val expiresAt: OffsetDateTime)
    data class View(val partyId: UUID, val minimumAgeMinutes: Int, val expiresAt: OffsetDateTime, val reason: String)

    @GET
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.mcpNativePasskeyAgeException.inspect")
    suspend fun read(@PathParam("partyId") partyId: UUID?): Response {
        staffActor()
        val party = requireNotNull(partyId)
        val row = latest(party) ?: throw ForbiddenException()
        if (row.getInteger("minimum_age_minutes") == null) throw NotFoundException()
        return response(
            View(
                party,
                row.getInteger("minimum_age_minutes"),
                row.getOffsetDateTime("expires_at"),
                row.getString("reason"),
            ),
        )
    }

    @GET
    @Path("/effective")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "party.mcpNativePasskeyAgeException.effective")
    suspend fun effective(@PathParam("partyId") partyId: UUID?): Response {
        requireEffectiveCaller()
        val row = latest(requireNotNull(partyId))?.takeIf { it.getInteger("minimum_age_minutes") != null }
            ?: throw NotFoundException()
        return response(Effective(row.getInteger("minimum_age_minutes"), row.getOffsetDateTime("expires_at")))
    }

    @PUT
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.mcpNativePasskeyAgeException.manage")
    suspend fun put(@PathParam("partyId") partyId: UUID?, change: Change?): Response {
        val actor = staffActor()
        val party = requireNotNull(partyId)
        val request = requireNotNull(change)
        val minutes = requireNotNull(request.minimumAgeMinutes).also {
            require(it in MIN_EXCEPTION_AGE_MINUTES..MAX_EXCEPTION_AGE_MINUTES)
        }
        val expiry = requireNotNull(request.expiresAt)
        val reason = requireNotNull(request.reason).trim().also {
            require(it.isNotEmpty() && it.length <= MAX_REASON_LENGTH && it.none(Char::isISOControl))
        }
        val result = pool.preparedQuery(
            """
            INSERT INTO mcp_native_passkey_age_exception_audit
                (party_id, actor_subject, minimum_age_minutes, expires_at, reason)
            SELECT party_id, $2, $3, $4, $5 FROM parties
            WHERE $ELIGIBLE_PARTY
                AND $4 > statement_timestamp()
                AND $4 <= statement_timestamp() + interval '24 hours'
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(party, actor, minutes, expiry, reason)).awaitSuspending()
        require(result.rowCount() == 1) { "Active synthetic party and expiry within 24 hours are required" }
        return response(View(party, minutes, expiry, reason))
    }

    @DELETE
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.mcpNativePasskeyAgeException.manage")
    suspend fun revoke(@PathParam("partyId") partyId: UUID?): Response {
        val actor = staffActor()
        pool.preparedQuery(
            """
            INSERT INTO mcp_native_passkey_age_exception_audit (party_id, actor_subject)
            SELECT party_id, $2 FROM parties
            WHERE party_id = $1 AND EXISTS (
                SELECT 1 FROM mcp_native_passkey_age_exception_audit WHERE party_id = $1
            )
            """.trimIndent(),
        ).execute(Tuple.of(requireNotNull(partyId), actor)).awaitSuspending()
        return Response.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    private fun requireEffectiveCaller() {
        if (!enabled) throw NotFoundException()
        if (!ServiceAccountIdentity.isPrincipal(identity, "service-account-openbank-edge")) throw ForbiddenException()
    }

    private fun staffActor(): String {
        if (!enabled || !ServiceAccountIdentity.isHumanStaff(identity)) throw ForbiddenException()
        return (identity.principal as? JsonWebToken)?.subject
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SUBJECT_LENGTH }
            ?: throw ForbiddenException()
    }

    private suspend fun latest(partyId: UUID) = pool.preparedQuery(
        """
        SELECT policy.minimum_age_minutes, policy.expires_at, policy.reason
        FROM parties
        LEFT JOIN LATERAL (
            SELECT minimum_age_minutes, expires_at, reason
            FROM mcp_native_passkey_age_exception_audit
            WHERE party_id = parties.party_id ORDER BY sequence_id DESC LIMIT 1
        ) policy ON policy.expires_at > statement_timestamp()
        WHERE $ELIGIBLE_PARTY
        """.trimIndent(),
    ).execute(Tuple.of(partyId)).awaitSuspending().firstOrNull()

    private fun response(entity: Any): Response =
        Response.ok(entity).header(HttpHeaders.CACHE_CONTROL, "no-store").build()

    private companion object {
        const val MIN_EXCEPTION_AGE_MINUTES = 1
        const val MAX_EXCEPTION_AGE_MINUTES = 4319
        const val MAX_REASON_LENGTH = 240
        const val MAX_SUBJECT_LENGTH = 255

        const val ELIGIBLE_PARTY = "parties.party_id = $1 AND party_type = 'INDIVIDUAL' " +
            "AND classification = 'SYNTHETIC' AND status = 'ACTIVE'"
    }
}
