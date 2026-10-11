// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.exit.PayoutService
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * Operator view of payouts across contracts (ADR-0334 S8): the admin payout queue, optionally for
 * one contract. Staff only — the participant reads its own payouts by id under its contract.
 */
@Path("/api/v2/pension/operator/payouts")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class PensionOperatorPayoutResource {

    @Inject
    lateinit var payouts: PayoutService

    @Inject
    lateinit var access: ContractAccessGuard

    @GET
    @Operation(summary = "Payouts newest first, optionally by status and contract")
    @Authorize(action = "pension.operator.exit-read")
    suspend fun list(
        @QueryParam("status") status: PayoutStatus?,
        @QueryParam("contractId") contractId: UUID?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<PayoutResponse> {
        check(access.readerFor(null) == Caller.STAFF) { "the payout queue is staff work" }
        return payouts.list(status, contractId, limit).map(PayoutResponse::from)
    }
}
