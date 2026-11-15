// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.StaffIdentities
import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogForbiddenException
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import com.openbank.productcatalog.infrastructure.security.CatalogScopeRoleMapper
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.OffsetDateTime
import java.util.UUID

@Path("/api/v2/passport-grants")
@Consumes(MediaType.APPLICATION_JSON)
class PassportGrantResource(
    private val pool: Pool,
    private val identity: SecurityIdentity,
    private val scopeMapper: CatalogScopeRoleMapper,
    @ConfigProperty(name = "openbank.catalog.security.scope-claim", defaultValue = "scope")
    private val scopeClaim: String,
) {
    @POST
    @RolesAllowed(CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.author")
    suspend fun propose(request: PassportGrantProposal): Response {
        val maker = requireStaff(CatalogRoles.AUTHOR)
        request.validate()
        val grant = UUID.randomUUID()
        pool.preparedQuery(INSERT).execute(
            Tuple.tuple().addUUID(grant)
                .addUUID(request.bankId).addUUID(request.establishmentId)
                .addString(request.hostCountry).addString(request.mode)
                .addUUID(request.offeringId).addString(request.channel).addString(request.customerSegment)
                .addString(request.packSetDigest).addString(request.evidenceRef).addUUID(maker)
                .addOffsetDateTime(request.effectiveFrom).addOffsetDateTime(request.effectiveUntil),
        ).awaitSuspending()
        return Response.status(Response.Status.CREATED).type(MediaType.APPLICATION_JSON_TYPE)
            .entity(PassportGrantCreated(grant)).build()
    }

    @POST
    @Path("/{grantId}/approve")
    @RolesAllowed(CatalogRoles.PUBLISH)
    @Authorize(action = "catalog.publish", resource = "#grantId")
    suspend fun approve(@PathParam("grantId") grantId: UUID, request: PassportGrantDecision): Response {
        val checker = requireStaff(CatalogRoles.PUBLISH)
        val approved = pool.preparedQuery("SELECT approve_passport_grant($1, $2, $3)")
            .execute(Tuple.of(grantId, checker, request.checkedReason())).awaitSuspending().first().getBoolean(0)
        if (!approved) throw CatalogConflictException("passport grant cannot be approved")
        return Response.noContent().build()
    }

    @POST
    @Path("/{grantId}/revoke")
    @RolesAllowed(CatalogRoles.PUBLISH)
    @Authorize(action = "catalog.publish", resource = "#grantId")
    suspend fun revoke(@PathParam("grantId") grantId: UUID, request: PassportGrantDecision): Response {
        val checker = requireStaff(CatalogRoles.PUBLISH)
        val revoked = pool.preparedQuery("SELECT revoke_passport_grant($1, $2, $3)")
            .execute(Tuple.of(grantId, checker, request.checkedReason())).awaitSuspending().first().getBoolean(0)
        if (!revoked) throw CatalogConflictException("passport grant cannot be revoked")
        return Response.noContent().build()
    }

    private fun requireStaff(role: String): UUID {
        val token = identity.principal as? JsonWebToken
        val staff = StaffIdentities.from(token)
        val person = staff.person
        val allowed = token != null &&
            token.getClaim<String>("azp") == "openbank-admin-ui" &&
            "ROLE_API" !in identity.roles &&
            staff.strongAuth &&
            person != null &&
            role in scopeMapper.roles(token.getClaim<Any?>(scopeClaim), identity.roles)
        if (!allowed) throw CatalogForbiddenException("staff identity, catalogue authority and MFA are required")
        return UUID.nameUUIDFromBytes("passport-staff:${requireNotNull(person)}".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        val INSERT = """
            INSERT INTO passport_grants (id, bank_id, establishment_id, host_country, mode, offering_id,
                channel, customer_segment, pack_set_digest, evidence_ref, proposed_by, effective_from, effective_until)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
        """.trimIndent()
    }
}

data class PassportGrantProposal(
    val bankId: UUID,
    val establishmentId: UUID,
    val hostCountry: String,
    val mode: String,
    val offeringId: UUID,
    val channel: String,
    val customerSegment: String,
    val packSetDigest: String,
    val evidenceRef: String,
    val effectiveFrom: OffsetDateTime,
    val effectiveUntil: OffsetDateTime,
) {
    fun validate() {
        require(hostCountry in EU_HOSTS && mode in setOf("FOS", "BRANCH")) { "unsupported passport country or mode" }
        require(effectiveUntil.isAfter(effectiveFrom)) { "invalid effective interval" }
        require(listOf(channel, customerSegment, evidenceRef).all { it.isNotBlank() && it.length <= MAX_FIELD })
        require(DIGEST.matches(packSetDigest)) { "invalid pack-set digest" }
    }

    private companion object {
        const val MAX_FIELD = 240
        val DIGEST = Regex("^[a-f0-9]{64}$")
        val EU_HOSTS = setOf(
            "AT", "BE", "BG", "CY", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU",
            "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
        )
    }
}

data class PassportGrantDecision(val reason: String) {
    fun checkedReason(): String = reason.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_REASON_LENGTH) { "decision requires a bounded reason" }
    }
}

private const val MAX_REASON_LENGTH = 240

data class PassportGrantCreated(val grantId: UUID)
