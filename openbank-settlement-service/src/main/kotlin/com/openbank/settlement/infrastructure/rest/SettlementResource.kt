// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.domain.model.Settlement
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Origination endpoint for interbank settlements. A POST creates a PENDING settlement and starts
 * its durable settlement workflow (ADR-0101 P3). Money-path: guarded by a coarse role gate plus
 * the fine-grained `settlement.create` OPA action, enforced (ADR-0034 Phase 5, issue #266) — an
 * OPA sidecar is deployed with `settlement_rest_ext.rego` and `AUTHZ_ENFORCE=true`. Only
 * ROLE_OPERATOR/ROLE_ADMIN are granted by policy; the SERVICE role above remains valid RBAC but
 * has no OPA allow rule (no verified in-repo M2M caller — see settlement_rest_ext.rego).
 */
@Path("/api/v1/settlements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Settlements", description = "Interbank settlement origination")
class SettlementResource(private val settlementUseCase: SettlementUseCase) {

    @POST
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.create", resource = "")
    @Operation(summary = "Originate a settlement and start its workflow")
    suspend fun originate(request: CreateSettlementRequest): Response {
        validate(request)
        val settlement = settlementUseCase.originate(
            OriginateSettlementCommand(
                idempotencyKey = request.idempotencyKey,
                payerAccountId = request.payerAccountId,
                payeeAccountId = request.payeeAccountId,
                amount = request.amount,
                currency = request.currency,
            ),
        )
        return Response.created(URI.create("/api/v1/settlements/${settlement.id}"))
            .entity(settlement.toResponse())
            .build()
    }

    /** Reject malformed money-path input with 400 before any settlement is created. */
    private fun validate(request: CreateSettlementRequest) {
        val errors = buildList {
            if (request.idempotencyKey.isBlank()) add("idempotencyKey must not be blank")
            if (request.amount <= BigDecimal.ZERO) add("amount must be positive")
            if (!CURRENCY_CODE.matches(request.currency)) add("currency must be an uppercase 3-letter ISO-4217 code")
            if (request.payerAccountId == request.payeeAccountId) add("payer and payee accounts must differ")
        }
        if (errors.isNotEmpty()) {
            throw BadRequestException(errors.joinToString("; "))
        }
    }

    private companion object {
        val CURRENCY_CODE = Regex("[A-Z]{3}")
    }
}

data class CreateSettlementRequest(
    val idempotencyKey: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
)

data class SettlementResponse(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

private fun Settlement.toResponse() = SettlementResponse(
    id = id,
    payerAccountId = payerAccountId,
    payeeAccountId = payeeAccountId,
    amount = amount,
    currency = currency,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
