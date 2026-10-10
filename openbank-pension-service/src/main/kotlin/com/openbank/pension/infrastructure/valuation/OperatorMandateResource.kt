// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.valuation

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.usecase.PaymentMandate
import com.openbank.pension.application.usecase.PaymentMandateService
import com.openbank.pension.application.usecase.PaymentMandateStatus
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import java.time.Instant
import java.util.UUID

/**
 * Operator view of the standing-order / SEPA direct-debit mandates pension-service set up
 * (#12378), for admin-ui. Staff only, three times over: `@RolesAllowed` has no ROLE_API, OPA's
 * `pension.operator.mandate-read` admits only real human staff (never a service-account, never the
 * edge), and a request carrying a participant header is refused here — participants see their
 * mandates through their own contract, not through the operator list.
 */
@Path("/api/v1/pension/operator/mandates")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class OperatorMandateResource {

    @Inject
    lateinit var mandates: PaymentMandateService

    @Inject
    lateinit var access: ContractAccessGuard

    @GET
    @Operation(summary = "Payment mandates newest first, optionally by contract and status (staff only)")
    @Authorize(action = "pension.operator.mandate-read")
    suspend fun list(
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
        @QueryParam("contractId") contractId: UUID?,
        @QueryParam("status") status: PaymentMandateStatus?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<MandateListItem> {
        if (party != null || access.readerFor(null) != Caller.STAFF) {
            throw ForbiddenException("the mandates list is staff work")
        }
        return mandates.list(contractId, status, limit).map(MandateListItem::from)
    }
}

data class MandateListItem(
    val id: UUID,
    val contractId: UUID,
    val kind: String,
    val externalId: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(m: PaymentMandate) =
            MandateListItem(m.id, m.contractId, m.kind.name, m.externalId, m.status.name, m.createdAt, m.updatedAt)
    }
}
