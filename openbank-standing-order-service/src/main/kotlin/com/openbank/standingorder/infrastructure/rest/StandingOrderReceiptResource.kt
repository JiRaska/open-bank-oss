// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.rest

import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.rest.dto.StandingOrderReceiptLookupRequest
import com.openbank.standingorder.infrastructure.rest.dto.StandingOrderReceiptLookupResponse
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID

@Path("/api/v1/standing-orders/receipt-lookup")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class StandingOrderReceiptResource(private val useCase: StandingOrderUseCase) {
    @POST
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun receiptLookup(
        req: StandingOrderReceiptLookupRequest,
        @HeaderParam("X-Customer-Party-Id") partyHeader: String?,
        @HeaderParam("X-Customer-Actor-Id") actorHeader: String?,
    ): StandingOrderReceiptLookupResponse {
        val party = parseCustomerId(partyHeader, "party")
        val actor = parseCustomerId(actorHeader, "actor")
        val receipt = useCase.findBoundReceipt(req.idempotencyKey, party, req.debitAccountId, actor)
        return if (receipt == null) {
            StandingOrderReceiptLookupResponse("UNKNOWN")
        } else {
            StandingOrderReceiptLookupResponse("FOUND", receipt.id, receipt.status)
        }
    }

    private fun parseCustomerId(value: String?, name: String): UUID =
        value?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw IllegalArgumentException("Missing or invalid customer $name")
}
