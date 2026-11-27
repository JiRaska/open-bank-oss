// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
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

@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class McpNativePasskeyAgePolicyResource(
    private val identity: SecurityIdentity,
    private val pool: Pool,
    @ConfigProperty(name = "openbank.party.sandbox-mcp-native-age-policy", defaultValue = "false")
    private val enabled: Boolean,
) {
    data class Change(val minimumAgeMinutes: Int?, val expiresAt: OffsetDateTime?, val reason: String?)
    data class Effective(val minimumAgeMinutes: Int, val expiresAt: OffsetDateTime)
    data class View(val minimumAgeMinutes: Int, val expiresAt: OffsetDateTime, val reason: String)

    @GET
    @Path("/mcp-native-passkey-age-policy")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.mcpNativePasskeyAgePolicy.inspect")
    suspend fun read(): Response {
        staffActor()
        val row = pool.preparedQuery(
            """
            SELECT minimum_age_minutes, expires_at, reason FROM ($LATEST) policy
            WHERE expires_at > statement_timestamp()
            """.trimIndent(),
        ).execute().awaitSuspending().firstOrNull() ?: throw NotFoundException()
        return response(view(row))
    }

    @PUT
    @Path("/mcp-native-passkey-age-policy")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.mcpNativePasskeyAgePolicy.manage")
    suspend fun put(change: Change?): Response {
        val actor = staffActor()
        val rule = validated(change)
        val result = pool.preparedQuery(
            """
            INSERT INTO mcp_native_passkey_sandbox_age_policy_audit (actor_subject, minimum_age_minutes, expires_at, reason)
            SELECT $1, $2, $3, $4
            WHERE $3 > statement_timestamp() AND $3 <= statement_timestamp() + interval '24 hours'
            """.trimIndent(),
        ).execute(Tuple.of(actor, rule.minimumAgeMinutes, rule.expiresAt, rule.reason)).awaitSuspending()
        require(result.rowCount() == 1) { "Expiry must be within the next 24 hours" }
        return response(rule)
    }

    @DELETE
    @Path("/mcp-native-passkey-age-policy")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.mcpNativePasskeyAgePolicy.manage")
    suspend fun revoke(): Response {
        val actor = staffActor()
        pool.preparedQuery("INSERT INTO mcp_native_passkey_sandbox_age_policy_audit (actor_subject) VALUES ($1)")
            .execute(Tuple.of(actor)).awaitSuspending()
        return Response.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    @GET
    @Path("/{partyId}/mcp-native-passkey-age-policy/effective")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "party.mcpNativePasskeyAgePolicy.effective")
    suspend fun effective(@PathParam("partyId") partyId: UUID?): Response {
        requireEffectiveCaller()
        val row = pool.preparedQuery(
            """
            SELECT policy.minimum_age_minutes, policy.expires_at
            FROM ($LATEST) policy
            WHERE policy.expires_at > statement_timestamp() AND EXISTS (
                SELECT 1 FROM parties WHERE party_id = $1 AND party_type = 'INDIVIDUAL' AND status = 'ACTIVE'
                    AND classification IN ('CUSTOMER', 'SYNTHETIC')
            )
            """.trimIndent(),
        ).execute(Tuple.of(requireNotNull(partyId))).awaitSuspending().firstOrNull() ?: throw NotFoundException()
        return response(Effective(row.getInteger("minimum_age_minutes"), row.getOffsetDateTime("expires_at")))
    }

    private fun requireEffectiveCaller() {
        if (!enabled) throw NotFoundException()
        if (!ServiceAccountIdentity.isPrincipal(identity, "service-account-openbank-edge")) throw ForbiddenException()
    }

    private fun staffActor(): String {
        if (!enabled || !ServiceAccountIdentity.isHumanStaff(identity)) throw ForbiddenException()
        return (identity.principal as? JsonWebToken)?.subject?.takeIf { it.isNotBlank() && it.length <= MAX_SUBJECT }
            ?: throw ForbiddenException()
    }

    private fun validated(change: Change?): View {
        val input = requireNotNull(change)
        val minutes = requireNotNull(input.minimumAgeMinutes)
        require(minutes in MIN_AGE..MAX_AGE)
        val reason = requireNotNull(input.reason).trim()
        require(reason.length in 1..MAX_REASON && reason.none(Char::isISOControl))
        return View(minutes, requireNotNull(input.expiresAt), reason)
    }

    private fun view(row: Row): View =
        View(row.getInteger("minimum_age_minutes"), row.getOffsetDateTime("expires_at"), row.getString("reason"))

    private fun response(entity: Any): Response =
        Response.ok(entity).header(HttpHeaders.CACHE_CONTROL, "no-store").build()

    private companion object {
        const val MIN_AGE = 1
        const val MAX_AGE = 4319
        const val MAX_REASON = 240
        const val MAX_SUBJECT = 255
        const val LATEST = "SELECT minimum_age_minutes, expires_at, reason " +
            "FROM mcp_native_passkey_sandbox_age_policy_audit ORDER BY sequence_id DESC LIMIT 1"
    }
}
