// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogForbiddenException
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/api/v2/internal/passport-commitments")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class PassportCommitmentResource(
    private val pool: Pool,
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.catalog.passport-commitment.schema-id") private val schemaId: String,
) {
    @POST
    suspend fun admit(request: PassportCommitmentRequest): Response {
        requireLendingClient()
        validateCommitment(request)
        val receipt = pool.preparedQuery(ADMIT).execute(request.arguments().addString(schemaId))
            .awaitSuspending().first().getOffsetDateTime(0)
        if (receipt == null) throw CatalogConflictException("passport commitment refused")
        return Response.noContent().build()
    }

    @POST
    @Path("/receipt")
    suspend fun receipt(request: PassportCommitmentRequest): Response {
        requireLendingClient()
        validateCommitment(request)
        val found = pool.preparedQuery(CONSUMED_RECEIPT).execute(request.arguments())
            .awaitSuspending().first().getBoolean(0)
        return if (found) Response.noContent().build() else Response.status(Response.Status.NOT_FOUND).build()
    }

    @POST
    @Path("/route")
    @Produces(MediaType.APPLICATION_JSON)
    suspend fun route(request: PassportRouteRequest): PassportCommitmentRoute {
        requireLendingClient()
        require(DIGEST.matches(request.revisionContentHash))
        val route = pool.preparedQuery(ROUTE)
            .execute(Tuple.of(request.grantId, request.partyId, request.revisionId, request.revisionContentHash, schemaId))
            .awaitSuspending().firstOrNull() ?: throw CatalogConflictException("passport route unavailable")
        return PassportCommitmentRoute(
            hostCountry = route.getString("host_country"),
            bankId = route.getUUID("bank_id"),
            establishmentId = route.getUUID("establishment_id"),
            channel = route.getString("channel"),
            customerSegment = route.getString("customer_segment"),
            packSetDigest = route.getString("pack_set_digest"),
        )
    }

    private fun requireLendingClient() {
        val token = identity.principal as? JsonWebToken
        if (token?.getClaim<String>("azp") != "openbank-lending" ||
            token.getClaim<String>("preferred_username") != "service-account-openbank-lending"
        ) {
            throw CatalogForbiddenException("the lending-service client is required")
        }
    }

    private fun validateCommitment(request: PassportCommitmentRequest) {
        require(request.operation in OPERATIONS && DIGEST.matches(request.binding))
        require(DIGEST.matches(request.revisionContentHash))
        require(COUNTRY.matches(request.hostCountry) && DIGEST.matches(request.packSetDigest))
        require(request.channel.isNotBlank() && request.customerSegment.isNotBlank())
    }

    private fun PassportCommitmentRequest.arguments(): Tuple = Tuple.tuple()
        .addUUID(grantId).addString(operation).addUUID(operationId).addUUID(partyId)
        .addUUID(revisionId).addString(binding).addString(hostCountry).addUUID(bankId)
        .addUUID(establishmentId).addString(channel).addString(customerSegment).addString(packSetDigest)
        .addString(revisionContentHash)

    private companion object {
        val ADMIT = """
            SELECT admit_passport_commitment($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
        """.trimIndent()
        val OPERATIONS = setOf("SIGN", "DISBURSE")
        val COUNTRY = Regex("^[A-Z]{2}$")
        val DIGEST = Regex("^[a-f0-9]{64}$")
        val ROUTE = """
            SELECT g.host_country::TEXT AS host_country, g.bank_id, g.establishment_id, g.channel,
                g.customer_segment, g.pack_set_digest
            FROM passport_grants g
            JOIN passport_grant_subject_evidence e ON e.grant_id = g.id
            JOIN catalog_offerings o ON o.id = g.offering_id
            JOIN catalog_revisions r ON r.offering_id = g.offering_id
            WHERE g.id = $1 AND e.party_id = $2 AND e.valid_until > statement_timestamp()
                AND r.id = $3 AND r.content_hash::TEXT = $4 AND r.schema_id = $5
                AND r.state = 'PUBLISHED' AND r.effective_from <= statement_timestamp()
                AND (r.effective_to IS NULL OR r.effective_to > statement_timestamp())
                AND g.mode = 'FOS' AND g.approved_by IS NOT NULL AND g.revoked_at IS NULL
                AND g.effective_from <= statement_timestamp() AND g.effective_until > statement_timestamp()
                AND o.market -> 'countries' ? g.host_country::TEXT
        """.trimIndent()
        val CONSUMED_RECEIPT = """
            SELECT EXISTS (
                SELECT 1 FROM passport_account_permits p
                JOIN passport_grants g ON g.id = p.grant_id
                JOIN catalog_revisions r ON r.id = p.product_revision_id
                WHERE grant_id = $1 AND operation = $2 AND operation_id = $3 AND party_id = $4
                    AND g.host_country::TEXT = $7 AND g.bank_id = $8 AND g.establishment_id = $9
                    AND g.channel = $10 AND g.customer_segment = $11 AND g.pack_set_digest = $12
                    AND product_revision_id = $5 AND binding_digest = $6 AND consumed_at IS NOT NULL
                    AND r.content_hash::TEXT = $13
            )
        """.trimIndent()
    }
}

data class PassportCommitmentRequest(
    val grantId: UUID,
    val operation: String,
    val operationId: UUID,
    val partyId: UUID,
    val revisionId: UUID,
    val revisionContentHash: String,
    val binding: String,
    val hostCountry: String,
    val bankId: UUID,
    val establishmentId: UUID,
    val channel: String,
    val customerSegment: String,
    val packSetDigest: String,
)

data class PassportRouteRequest(
    val grantId: UUID,
    val partyId: UUID,
    val revisionId: UUID,
    val revisionContentHash: String,
)

data class PassportCommitmentRoute(
    val hostCountry: String,
    val bankId: UUID,
    val establishmentId: UUID,
    val channel: String,
    val customerSegment: String,
    val packSetDigest: String,
)
