// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.usecase.ClassificationCorrectionRequest
import com.openbank.pensionfund.application.usecase.PositionClassificationService
import com.openbank.pensionfund.domain.model.ClassificationTarget
import com.openbank.pensionfund.domain.model.InstrumentClass
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.PositionClassificationCorrection
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Four-eyes reclassification of recorded fund positions (#12425). Proposing is a maker act on the
 * NAV book (`nav.calculate`), deciding a checker act (`nav.approve`); the service refuses a
 * proposer deciding their own correction. The positions themselves are read on [NavResource].
 */
@Tag(name = "NAV", description = "Four-eyes NAV publication and correction (ADR-0334)")
@Path("/api/v1/position-classification-corrections")
@Produces(MediaType.APPLICATION_JSON)
class LegacyPositionClassificationResource {

    @Inject
    lateinit var classification: PositionClassificationService

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Propose a position's instrument class (maker)",
        deprecated = true,
    )
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.nav.calculate")
    suspend fun propose(
        body: ClassificationCorrectionDto?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): ClassificationCorrectionResponse {
        val dto = requireNotNull(body) { "request body is required" }
        val request = ClassificationCorrectionRequest(
            positionId = requireNotNull(dto.positionId) { "positionId is required" },
            toClass = requireNotNull(dto.toClass) { "toClass is required" }.instrumentClass(),
            reason = requireNotNull(dto.reason) { "reason is required" },
        )
        return ClassificationCorrectionResponse.from(
            classification.propose(
                request,
                identity.actor(),
                requireNotNull(key) {
                    "Idempotency-Key is required"
                },
            ),
        )
    }

    @POST
    @Path("/{correctionId}/approve")
    @Operation(
        summary = "Approve a proposed instrument class (checker)",
        deprecated = true,
    )
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.nav.approve", resource = "#correctionId")
    suspend fun approve(
        @PathParam("correctionId") correctionId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
    ): ClassificationCorrectionResponse = ClassificationCorrectionResponse.from(
        classification.approve(
            correctionId,
            identity.actor(),
            requireNotNull(key) {
                "Idempotency-Key is required"
            },
        ),
    )

    @POST
    @Path("/{correctionId}/reject")
    @Operation(
        summary = "Reject a proposed instrument class (checker)",
        deprecated = true,
    )
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.nav.approve", resource = "#correctionId")
    suspend fun reject(
        @PathParam("correctionId") correctionId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
    ): ClassificationCorrectionResponse = ClassificationCorrectionResponse.from(
        classification.reject(
            correctionId,
            identity.actor(),
            requireNotNull(key) {
                "Idempotency-Key is required"
            },
        ),
    )
}
