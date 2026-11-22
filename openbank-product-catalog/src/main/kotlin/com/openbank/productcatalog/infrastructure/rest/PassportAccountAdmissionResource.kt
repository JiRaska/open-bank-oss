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

@Path("/api/v2/internal/passport-account-admissions")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class PassportAccountAdmissionResource(private val pool: Pool, private val identity: SecurityIdentity) {
    @POST
    suspend fun admit(request: PassportAccountAdmissionRequest): Response {
        requireAccountClient(request)
        val receipt = pool.preparedQuery("SELECT admit_passport_account($1, $2, $3, $4, $5, $6)")
            .execute(
                Tuple.of(
                    request.grantId,
                    request.operation,
                    request.operationId,
                    request.partyId,
                    request.revisionId,
                    request.binding,
                ),
            ).awaitSuspending().first().getOffsetDateTime(0)
        if (receipt == null) throw CatalogConflictException("passport account admission refused")
        return Response.noContent().build()
    }

    @POST
    @Path("/receipt")
    suspend fun receipt(request: PassportAccountAdmissionRequest): Response {
        requireAccountClient(request)
        val found = pool.preparedQuery(CONSUMED_RECEIPT).execute(
            Tuple.of(
                request.grantId,
                request.operation,
                request.operationId,
                request.partyId,
                request.revisionId,
                request.binding,
            ),
        ).awaitSuspending().first().getBoolean(0)
        return if (found) Response.noContent().build() else Response.status(Response.Status.NOT_FOUND).build()
    }

    private fun requireAccountClient(request: PassportAccountAdmissionRequest) {
        val token = identity.principal as? JsonWebToken
        if (token?.getClaim<String>("azp") != "openbank-account" ||
            token.getClaim<String>("preferred_username") != "service-account-openbank-account"
        ) {
            throw CatalogForbiddenException("the account-service client is required")
        }
        require(request.operation in OPERATIONS && BINDING.matches(request.binding))
    }

    private companion object {
        val OPERATIONS = setOf("OPEN", "ACTIVATE")
        val BINDING = Regex("^[a-f0-9]{64}$")
        val CONSUMED_RECEIPT = """
            SELECT EXISTS (
                SELECT 1 FROM passport_account_permits
                WHERE grant_id = $1 AND operation = $2 AND operation_id = $3 AND party_id = $4
                    AND product_revision_id = $5 AND binding_digest = $6 AND consumed_at IS NOT NULL
            )
        """.trimIndent()
    }
}

data class PassportAccountAdmissionRequest(
    val grantId: UUID,
    val operation: String,
    val operationId: UUID,
    val partyId: UUID,
    val revisionId: UUID,
    val binding: String,
)
