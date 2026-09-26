// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.domain.model.SettlementStatus
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/** Read persisted financial state independently of the one-use approval claim. */
@Path("/api/v1/settlements/{id}")
@Produces(MediaType.APPLICATION_JSON)
class SettlementQueryResource(private val settlements: SettlementUseCase) {
    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.status.read", resource = "#id")
    suspend fun get(@PathParam("id") id: String): Response {
        val key = UUID.fromString(id)
        require(key.toString().equals(id, ignoreCase = true)) { "id must be a canonical UUID" }
        val settlement = settlements.findById(key) ?: throw NotFoundException("Settlement not found")
        val detail = SettlementDetailsResponse(
            id = settlement.id,
            payerAccountId = settlement.payerAccountId,
            payeeAccountId = settlement.payeeAccountId,
            amount = settlement.amount.toPlainString(),
            currency = settlement.currency,
            status = settlement.status,
            createdAt = settlement.createdAt,
            updatedAt = settlement.updatedAt,
        )
        return Response.ok(detail).header("Cache-Control", "no-store").build()
    }
}

/** The read contract preserves uncertain states and exact amounts for operator reconciliation. */
data class SettlementDetailsResponse(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: String,
    val currency: String,
    val status: SettlementStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)
