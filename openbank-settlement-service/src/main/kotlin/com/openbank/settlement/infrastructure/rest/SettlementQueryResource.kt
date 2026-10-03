// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.domain.model.Settlement
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant
import java.util.UUID

/**
 * Read-only status query for operator reconciliation. It reads the persisted row and never starts,
 * resumes or retries the settlement workflow. The response is the same stable v1 vocabulary as
 * origination, so an uncertain balance movement reads as PENDING with `recoveryRequired=true`,
 * never as a success.
 *
 * `settlement.read` is admitted by the shared `operator-read-any` reason (HUMAN + ROLE_OPERATOR or
 * ROLE_ADMIN), so this needs no settlement-specific rego. That reason also admits a Keycloak
 * service-account carrying ROLE_OPERATOR, and an additional allow rule could not take that away,
 * so the human-only restriction is enforced here.
 */
@Path("/api/v1/settlements/{id}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Settlements", description = "Interbank settlement origination")
class SettlementQueryResource(private val settlementUseCase: SettlementUseCase) {

    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.read", resource = "#id")
    @Operation(summary = "Read a settlement's persisted status")
    suspend fun get(@PathParam("id") id: String, @Context security: SecurityContext): Response {
        if (security.userPrincipal?.name.orEmpty().startsWith(SERVICE_ACCOUNT_PREFIX)) {
            throw ForbiddenException("Settlement status is a human operator read")
        }
        val settlement = settlementUseCase.findById(parseCanonicalUuid(id))
            ?: throw NotFoundException("Settlement not found")
        return Response.ok(SettlementStatusResponse.of(settlement)).header("Cache-Control", "no-store").build()
    }

    private fun parseCanonicalUuid(id: String): UUID {
        val parsed = runCatching { UUID.fromString(id) }.getOrNull()
        require(parsed != null && parsed.toString().equals(id, ignoreCase = true)) {
            "id must be a canonical UUID"
        }
        return parsed
    }

    private companion object {
        const val SERVICE_ACCOUNT_PREFIX = "service-account-"
    }
}

/**
 * The published `SettlementResponse` schema, with `amount` as exact decimal text as that schema
 * declares (a JSON number cannot carry 19,4 precision through a browser). Status and recovery
 * fields come from the same mapping as origination, so the two endpoints cannot drift apart.
 */
data class SettlementStatusResponse(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: String,
    val currency: String,
    val status: SettlementResponseStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val recoveryRequired: Boolean,
    val recoveryReason: String?,
) {
    companion object {
        fun of(settlement: Settlement): SettlementStatusResponse = settlement.toResponse().let {
            SettlementStatusResponse(
                id = it.id,
                payerAccountId = it.payerAccountId,
                payeeAccountId = it.payeeAccountId,
                amount = it.amount.toPlainString(),
                currency = it.currency,
                status = it.status,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                recoveryRequired = it.recoveryRequired,
                recoveryReason = it.recoveryReason,
            )
        }
    }
}
