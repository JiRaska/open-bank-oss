// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.application.port.`in`.ScoreFraudUseCase
import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.util.UUID

@Path("/api/v1/fraud")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Fraud")
class FraudResource {

    @Inject lateinit var scoreFraud: ScoreFraudUseCase

    /**
     * Real-time scoring endpoint (ADR-0084 §1). Called — once wired — by the four payment surfaces
     * after SCA, alongside the ADR-0032 sanctions/AML gate. Phase 1 is inert: the stub rule set
     * always returns ALLOW and no surface calls this yet. Service-to-service + operator roles,
     * mirroring the other money-path services.
     */
    @POST
    @Path("/score")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "fraud.score")
    @Operation(summary = "Score a payment intent and return a fraud verdict (ALLOW/CHALLENGE/REVIEW/DECLINE)")
    suspend fun score(req: ScoreFraudRequest): Response {
        val result: FraudScore = scoreFraud.score(req.toDomain())
        return Response.ok(result.toResponse()).build()
    }

    @GET
    @Path("/review-queue")
    @RolesAllowed("ROLE_COMPLIANCE", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "fraud.review.read")
    @Operation(summary = "Analyst review queue: newest REVIEW verdicts (ADR-0230)")
    suspend fun reviewQueue(@QueryParam("limit") @DefaultValue("50") limit: Int): Response {
        val rows = scoreFraud.reviewQueue("REVIEW", limit)
        return Response.ok(rows).build()
    }
}

data class ScoreFraudRequest(
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID? = null,
    val counterpartyId: UUID? = null,
)

data class ScoreFraudResponse(val verdict: String, val score: Int, val reasons: List<String>, val ruleVersion: String)

private fun ScoreFraudRequest.toDomain() = ScoreRequest(
    amount = amount,
    currency = currency,
    rail = rail,
    accountId = accountId,
    counterpartyId = counterpartyId,
)

private fun FraudScore.toResponse() = ScoreFraudResponse(
    verdict = verdict.name,
    score = score,
    reasons = reasons,
    ruleVersion = ruleVersion,
)
