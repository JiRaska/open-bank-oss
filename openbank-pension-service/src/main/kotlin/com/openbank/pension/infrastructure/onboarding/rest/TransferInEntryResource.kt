// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.infrastructure.authz.ContractAccessGuard.Companion.PARTY_HEADER
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The route the customer edge uses to start a transfer-in (ADR-0334 S2). It is the onboarding
 * start with `kind = TRANSFER_IN` — same eligibility, KYC reuse, questionnaire, KID and SCA steps,
 * continued on `/api/v2/pension/onboarding/applications/{id}/...` — so there is one flow, not two.
 */
@Tag(name = "Pension onboarding", description = "Digital onboarding of a new pension contract or a transfer-in")
@Path("/api/v2/pension/contracts/transfers-in")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API)
class TransferInEntryResource {

    @Inject
    lateinit var onboarding: OnboardingResource

    @POST
    @Operation(summary = "Start a transfer-in application (onboarding with kind TRANSFER_IN)")
    @Authorize(action = "pension.onboarding.start")
    suspend fun start(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: StartApplicationRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        requireNotNull(body.transferIn) { "transferIn is required" }
        return onboarding.start(idempotencyKey, party, body.copy(kind = OnboardingKind.TRANSFER_IN))
    }
}
