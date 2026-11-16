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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/api/v2/internal/passport-loan-admissions")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class PassportLoanAdmissionResource(private val pool: Pool, private val identity: SecurityIdentity) {
    @POST
    suspend fun admit(request: PassportLoanAdmissionRequest): Response {
        requireLendingClient(request)
        val receipt = pool.preparedQuery(ADMIT).execute(request.arguments())
            .awaitSuspending().first().getOffsetDateTime(0)
        if (receipt == null) throw CatalogConflictException("passport loan admission refused")
        return Response.noContent().build()
    }

    @POST
    @Path("/receipt")
    suspend fun receipt(request: PassportLoanAdmissionRequest): Response {
        requireLendingClient(request)
        val found = pool.preparedQuery(CONSUMED_RECEIPT).execute(request.arguments())
            .awaitSuspending().first().getBoolean(0)
        return if (found) Response.noContent().build() else Response.status(Response.Status.NOT_FOUND).build()
    }

    private fun requireLendingClient(request: PassportLoanAdmissionRequest) {
        val token = identity.principal as? JsonWebToken
        if (token?.getClaim<String>("azp") != "openbank-lending" ||
            token.getClaim<String>("preferred_username") != "service-account-openbank-lending"
        ) {
            throw CatalogForbiddenException("the lending-service client is required")
        }
        require(request.operation in OPERATIONS && BINDING.matches(request.binding))
        require(COUNTRY.matches(request.hostCountry) && BINDING.matches(request.packSetDigest))
        require(request.channel.isNotBlank() && request.customerSegment.isNotBlank())
    }

    private fun PassportLoanAdmissionRequest.arguments(): Tuple = Tuple.tuple()
        .addUUID(grantId).addString(operation).addUUID(operationId).addUUID(partyId)
        .addUUID(revisionId).addString(binding).addString(hostCountry).addUUID(bankId)
        .addUUID(establishmentId).addString(channel).addString(customerSegment).addString(packSetDigest)

    private companion object {
        const val ADMIT = "SELECT admit_passport_loan($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)"
        val OPERATIONS = setOf("SIGN_LOAN", "DISBURSE_LOAN")
        val COUNTRY = Regex("^[A-Z]{2}$")
        val BINDING = Regex("^[a-f0-9]{64}$")
        val CONSUMED_RECEIPT = """
            SELECT EXISTS (
                SELECT 1 FROM passport_account_permits p
                JOIN passport_grants g ON g.id = p.grant_id
                WHERE grant_id = $1 AND operation = $2 AND operation_id = $3 AND party_id = $4
                    AND g.host_country::TEXT = $7 AND g.bank_id = $8 AND g.establishment_id = $9
                    AND g.channel = $10 AND g.customer_segment = $11 AND g.pack_set_digest = $12
                    AND product_revision_id = $5 AND binding_digest = $6 AND consumed_at IS NOT NULL
            )
        """.trimIndent()
    }
}

data class PassportLoanAdmissionRequest(
    val grantId: UUID,
    val operation: String,
    val operationId: UUID,
    val partyId: UUID,
    val revisionId: UUID,
    val binding: String,
    val hostCountry: String,
    val bankId: UUID,
    val establishmentId: UUID,
    val channel: String,
    val customerSegment: String,
    val packSetDigest: String,
)
