// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.rest

import com.openbank.loyalty.application.usecase.EarnLeavesUseCase
import com.openbank.loyalty.application.usecase.ProvisioningSummaryUseCase
import com.openbank.loyalty.application.usecase.ReadLeafSummaryUseCase
import com.openbank.loyalty.application.usecase.RedeemBenefitUseCase
import com.openbank.loyalty.domain.AnnualCap
import com.openbank.loyalty.domain.BenefitCatalog
import com.openbank.loyalty.domain.EarnCatalog
import com.openbank.loyalty.domain.EarnOutcome
import com.openbank.loyalty.domain.LeafEarnSource
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.RedemptionOutcome
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

data class EarnRequest(val earnSourceId: String, val correlationEventId: UUID)
data class RedeemRequest(val benefitId: String)

/**
 * The Lístek API. Party-scoped reads are reached through the customer edge, which injects the
 * caller's authoritative partyId — a client-supplied partyId never reaches this service on its own
 * authority, the same arrangement `openbank-engagement-service`'s surface API uses.
 *
 * Every parameter that JAX-RS can fail to inject is declared NULLABLE. A non-nullable
 * `@HeaderParam` is a 500 for the absent-header case and a `require` in the body is dead code:
 * a plain `fun` emits `checkNotNullParameter` at offset 0, so the NPE has already thrown, and a
 * `suspend fun` emits no intrinsic at all, so a null flows into a body that promised it could not
 * exist. Three services shipped "Idempotency-Key header is required" guards that answered 500 for
 * the exact case they were written for. libs-runtime maps IllegalArgumentException to 400, so
 * `requireNotNull` in the body is the correct and sufficient guard once the type is nullable.
 */
@Path("/api/v1/loyalty")
@ApplicationScoped
class LoyaltyResource(
    private val earn: EarnLeavesUseCase,
    private val redeem: RedeemBenefitUseCase,
    private val summary: ReadLeafSummaryUseCase,
    private val provisioning: ProvisioningSummaryUseCase,
) {

    @GET
    @Path("/parties/{partyId}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    suspend fun summary(@PathParam("partyId") partyId: UUID): Response {
        val s = summary.summarise(partyId)
        return Response.ok(
            mapOf(
                "partyId" to s.partyId.toString(),
                "balance" to s.balance.value,
                "earnedThisYear" to s.earnedThisYear.value,
                "earnedTotal" to s.earnedTotal().value,
                "nextExpiry" to s.nextExpiry?.toString(),
                "history" to s.history.map { it.toDto() },
            ),
        ).build()
    }

    @GET
    @Path("/benefits")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    fun benefits(): Response = Response.ok(
        BenefitCatalog.ALL.values.map {
            mapOf(
                "id" to it.id,
                "engine" to it.engine.name,
                "priceLeaves" to it.price.value,
                "validityDays" to it.validity.toDays(),
                "description" to it.description,
            )
        },
    ).build()

    @GET
    @Path("/provisioning")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    suspend fun provisioning(): Response {
        val s = provisioning.summarise()
        return Response.ok(
            mapOf(
                "at" to s.at.toString(),
                // The obligation, in Lístky. Deliberately not converted to any currency here: this
                // service does not price a Lístek, and the recognition policy that does belongs to
                // finance and to openbank-billing-service, which owns the journal (ADR-0282 D5).
                "outstandingLeaves" to s.outstandingLeaves,
                "annualCapPerParty" to AnnualCap.PER_PARTY_PER_YEAR.value,
                "ruleVersion" to EarnCatalog.RULE_VERSION,
            ),
        ).build()
    }

    @GET
    @Path("/earn-sources")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    fun earnSources(): Response = Response.ok(
        LeafEarnSource.ALL.map { source ->
            val rule = EarnCatalog.ruleFor(source)
            mapOf(
                "id" to source.id,
                "leaves" to rule.leaves.value,
                "validityDays" to rule.validity.toDays(),
            )
        },
    ).build()

    @POST
    @Path("/parties/{partyId}/earn")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API")
    suspend fun earn(@PathParam("partyId") partyId: UUID, request: EarnRequest): Response {
        val source = LeafEarnSource.byId(request.earnSourceId)
            ?: return badRequest("unknown earn source '${request.earnSourceId}'")

        return when (val outcome = earn.earn(partyId, source, request.correlationEventId)) {
            is EarnOutcome.Awarded -> Response.status(Response.Status.CREATED)
                .entity(mapOf("outcome" to "AWARDED", "entry" to outcome.entry.toDto()))
                .build()
            // 200, not 201: nothing was created. A replay is a legitimate answer to a redelivered
            // event, not an error, and it must not read as a second award.
            is EarnOutcome.AlreadyAwarded -> Response.ok(
                mapOf("outcome" to "ALREADY_AWARDED", "entry" to outcome.entry.toDto()),
            ).build()
            // 200 with its own outcome value. Not 201 (nothing was awarded) and not an error (the
            // request was valid and the programme is working as designed) — the caller has to be
            // able to tell this from a grant, which is why it is never folded into either.
            is EarnOutcome.Capped -> Response.ok(
                mapOf(
                    "outcome" to "CAPPED",
                    "requestedLeaves" to outcome.requested.value,
                    "remainingLeaves" to outcome.remaining.value,
                ),
            ).build()
        }
    }

    @POST
    @Path("/parties/{partyId}/redeem")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API")
    suspend fun redeem(
        @PathParam("partyId") partyId: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: RedeemRequest,
    ): Response {
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.isNotBlank()) { "header 'Idempotency-Key' must not be blank" }

        return when (val outcome = redeem.redeem(partyId, request.benefitId, idempotencyKey)) {
            is RedemptionOutcome.Granted -> Response.status(Response.Status.CREATED)
                .entity(grantDto(outcome.grant.id, outcome.grant.benefitId, outcome.grant.status.name, outcome))
                .build()
            is RedemptionOutcome.AlreadyGranted -> Response.ok(
                grantDto(outcome.grant.id, outcome.grant.benefitId, outcome.grant.status.name, outcome),
            ).build()
            is RedemptionOutcome.InsufficientLeaves -> Response.status(Response.Status.CONFLICT).entity(
                mapOf(
                    "outcome" to "INSUFFICIENT_LEAVES",
                    "requiredLeaves" to outcome.required.value,
                    "availableLeaves" to outcome.available.value,
                ),
            ).build()
            is RedemptionOutcome.UnknownBenefit ->
                badRequest("unknown benefit '${outcome.benefitId}'")
        }
    }

    private fun grantDto(id: UUID, benefitId: String, status: String, outcome: RedemptionOutcome) = mapOf(
        "outcome" to if (outcome is RedemptionOutcome.AlreadyGranted) "ALREADY_GRANTED" else "GRANTED",
        "grantId" to id.toString(),
        "benefitId" to benefitId,
        // GRANTED means the benefit is owed and published for its delivering engine. It does NOT
        // mean the engine has applied it; no field in this response asserts that.
        "grantStatus" to status,
    )

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to message)).build()

    private fun LeafLedgerEntry.toDto() = mapOf(
        "id" to id.toString(),
        "type" to type.name,
        "leaves" to leaves.value,
        "remainingLeaves" to remaining.value,
        "earnSourceId" to earnSource?.id,
        "benefitId" to benefitId,
        "ruleVersion" to ruleVersion,
        "occurredAt" to occurredAt.toString(),
        "expiresAt" to expiresAt?.toString(),
    )
}
