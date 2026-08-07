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
        req.validate()
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

/**
 * Reject a malformed scoring request at the HTTP boundary, as a 400.
 *
 * Nothing validated `currency` before this, and Jackson **coerces** a JSON scalar of the wrong type
 * into a `String` rather than rejecting it: `{"currency": false}` deserialises to the five-character
 * `"false"`. That reached `fraud_scores.currency`, a `varchar(3)`, and Postgres raised
 * `22001 value too long`, which surfaced as a Hibernate `DataException` and — with no mapper for it
 * — a **500** from `GenericExceptionMapper`. The 2026-08-03 `api-fuzz-authenticated` run
 * (30804842325, job 91657718387) reported it as this service's only `Server error`, on exactly that
 * body. A wrongly-typed field is a client error; reporting it as 5xx also charges a money-path
 * service's error budget for someone else's bad request.
 *
 * Deliberately `require` (-> `IllegalArgumentException` -> 400 via `openbank-libs-runtime`'s
 * `CommonExceptionMappers`) rather than a service-local `ExceptionMapper`: libs owns the mappers for
 * JDK types, and a second mapper for one type is picked non-deterministically per request.
 *
 * The bound is the *storage* contract, so it cannot silently drift back: `currency` must be exactly
 * the three upper-case letters ISO 4217 defines and the column holds.
 */
private fun ScoreFraudRequest.validate() {
    require(currency.matches(ISO_4217)) { "currency must be a 3-letter ISO 4217 code" }
    require(rail.isNotBlank()) { "rail is required" }
}

private val ISO_4217 = Regex("^[A-Z]{3}$")

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
