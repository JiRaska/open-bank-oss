// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import jakarta.annotation.security.RolesAllowed
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

    /**
     * `settlement.create` is in rules.yaml `four_eyes.actions` (#10041 slice 10). With
     * `authz.four-eyes.enforce=true` the interceptor answers 202 with an `approvalId` before this
     * body runs; the maker retries the IDENTICAL request with `X-Approval-Id` once a different
     * operator approved it, and the approval is consumed exactly once. Validation therefore lives
     * in [CreateSettlementRequest]'s constructor, which runs at deserialisation, BEFORE the
     * interceptor: a malformed instruction never parks an approval a checker could approve.
     */
    @POST
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.create", resource = "")
    @Operation(summary = "Originate a settlement and start its workflow")
    suspend fun originate(request: CreateSettlementRequest?): Response {
        requireNotNull(request) { "a request body is required" }
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
}

data class CreateSettlementRequest(
    val idempotencyKey: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
) {
    // Jackson constructs this before AuthorizeInterceptor runs, so an invalid instruction is
    // refused (400 via libs-runtime's IllegalArgumentException mapping) before it can park or
    // consume a four-eyes approval.
    init {
        val errors = buildList {
            if (idempotencyKey.isBlank()) add("idempotencyKey must not be blank")
            if (amount <= BigDecimal.ZERO) add("amount must be positive")
            if (!CURRENCY_CODE.matches(currency)) add("currency must be an uppercase 3-letter ISO-4217 code")
            if (payerAccountId == payeeAccountId) add("payer and payee accounts must differ")
        }
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private companion object {
        val CURRENCY_CODE = Regex("[A-Z]{3}")
    }
}

data class SettlementResponse(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: SettlementResponseStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val recoveryRequired: Boolean = false,
    val recoveryReason: String? = null,
)

private fun Settlement.toResponse() = SettlementResponse(
    id = id,
    payerAccountId = payerAccountId,
    payeeAccountId = payeeAccountId,
    amount = amount,
    currency = currency,
    // Preserve the v1 status vocabulary: an uncertain movement is still pending settlement.
    status = SettlementResponseStatus.fromDomain(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    recoveryRequired = status == SettlementStatus.BALANCE_STATE_UNKNOWN,
    recoveryReason = status.takeIf { it == SettlementStatus.BALANCE_STATE_UNKNOWN }?.name,
)
