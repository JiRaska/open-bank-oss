// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.domain.model.validateSettlementAmount
import com.openbank.settlement.infrastructure.approval.SettlementProposalStore
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
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
class SettlementResource(
    private val execution: SettlementApprovedExecution,
    private val proposals: SettlementProposalStore,
    private val identity: SecurityIdentity,
    @param:ConfigProperty(name = "authz.four-eyes.enforce", defaultValue = "false") private val fourEyes: Boolean,
) {

    @POST
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.proposal.create", resource = "#request.approvalFingerprint")
    @Operation(summary = "Originate a settlement and start its workflow")
    suspend fun originate(request: CreateSettlementRequest?): Response {
        requireNotNull(request) { "a request body is required" }
        if (fourEyes) proposals.capture(request, identity.principal.name)
        return execution.originate(request)
    }
}

/** The injected CDI boundary retains the original gate immediately before the financial write. */
@ApplicationScoped
class SettlementApprovedExecution(private val settlementUseCase: SettlementUseCase) {
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.create", resource = "#request.approvalFingerprint")
    suspend fun originate(request: CreateSettlementRequest): Response {
        val settlement = settlementUseCase.originate(
            request.toCommand(),
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
    @get:JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val currency: String,
) {
    // Jackson constructs this value before the authorization interceptor runs. An invalid
    // instruction must neither create a pending approval nor consume an approved one.
    init {
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        validateSettlementAmount(amount)
        require(CURRENCY_CODE.matches(currency)) { "currency must be an uppercase 3-letter ISO-4217 code" }
        require(payerAccountId != payeeAccountId) { "payer and payee accounts must differ" }
    }

    fun toCommand() = OriginateSettlementCommand(idempotencyKey, payerAccountId, payeeAccountId, amount, currency)

    /** Versioned binding to every submitted money instruction; never a caller-supplied digest. */
    @get:JsonIgnore
    val approvalFingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256")
            val fields = listOf(
                "settlement.create.v1",
                idempotencyKey,
                payerAccountId.toString(),
                payeeAccountId.toString(),
                amount.stripTrailingZeros().toString(),
                currency,
            )
            for (value in fields) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return HexFormat.of().formatHex(digest.digest())
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
