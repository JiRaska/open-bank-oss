// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.rest

import com.openbank.analytics.application.ErasureDecision
import com.openbank.analytics.application.ErasureService
import com.openbank.libs.security.Roles
import com.openbank.libs.security.actorName
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.SecurityContext

/** Body for a GDPR Art. 17 erasure request against the analytics layer. */
data class ErasureRequestDto(val aggregateType: String, val aggregateId: String)

/**
 * GDPR Art. 17 ("right to erasure") surface for the analytics layer (ADR-0023, F6).
 *
 * Gated to [Roles.COMPLIANCE]/[Roles.ADMIN] — erasure is a compliance action with legal weight, never
 * `@PermitAll`. The service applies the per-category retention policy: data under an AML/accounting
 * statutory hold is *refused* with an auditable legal basis (Art. 17(3)(b)); erasable categories are
 * crypto-shredded. The [ErasureDecision] is the audit record of what was (or was not) erased and why.
 */
@Path("/api/v1/analytics/erasure")
@Produces(MediaType.APPLICATION_JSON)
class ErasureResource {

    @Inject lateinit var service: ErasureService

    @POST
    @RolesAllowed(Roles.COMPLIANCE, Roles.ADMIN)
    suspend fun erase(@Context ctx: SecurityContext, dto: ErasureRequestDto): ErasureDecision =
        service.erase(dto.aggregateType, dto.aggregateId, ctx.actorName)
}
