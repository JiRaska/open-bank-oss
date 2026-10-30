// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.application.port.out.ThresholdSetProposal
import com.openbank.fraud.application.usecase.FraudThresholdActivationService
import com.openbank.fraud.domain.rules.FraudThresholdSet
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Four-eyes administration of the fraud money-threshold set (ADR-0347). The maker proposes a
 * complete new version; a DIFFERENT risk/compliance principal decides it. A proposal changes no
 * verdict; an approval is in force on every replica from its `activeFrom`.
 *
 * Error mapping is libs-runtime's, deliberately (#526): invalid content or a non-decidable proposal
 * is an `IllegalArgumentException` (400), a self-approval a `MakerCheckerViolation` — an
 * `IllegalStateException` — (422). No service-local mapper.
 */
@Path("/api/v1/fraud/thresholds")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Fraud thresholds", description = "Four-eyes activation of fraud rule money thresholds")
@RolesAllowed("ROLE_RISK", "ROLE_COMPLIANCE", "ROLE_ADMIN")
class FraudThresholdResource {

    @Inject lateinit var activations: FraudThresholdActivationService

    @Inject lateinit var identity: SecurityIdentity

    private fun actor(): String = identity.principal?.name.orEmpty()

    @POST
    @Path("/proposals")
    @Authorize(action = "fraud.thresholds.propose")
    @Operation(summary = "Propose a new threshold-set version for four-eyes activation (maker)")
    suspend fun propose(request: ProposeThresholdsRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val set = FraudThresholdSet(
            version = requireNotNull(body.version) { "version is required" },
            thresholds = requireNotNull(body.thresholds) { "thresholds is required" }
                .mapValues { (rule, byCcy) ->
                    byCcy.mapValues { (ccy, amount) ->
                        requireNotNull(amount?.takeIf { it.matches(PLAIN_DECIMAL) }?.toBigDecimal()) {
                            "rule '$rule' $ccy: amount must be a plain decimal string (no exponent, <= 16.4 digits)"
                        }
                    }
                },
        )
        val created = activations.propose(set, body.effectiveFrom, actor())
        return Response.status(HTTP_CREATED).entity(created.toView()).build()
    }

    @POST
    @Path("/proposals/{id}/decide")
    @Authorize(action = "fraud.thresholds.decide", resource = "#id")
    @Operation(summary = "Approve or reject a threshold-set proposal (checker, must differ from maker)")
    suspend fun decide(@PathParam("id") id: UUID, request: DecideThresholdsRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val approve = requireNotNull(body.approve) { "approve is required" }
        return Response.ok(activations.decide(id, approve, actor(), body.reason).toView()).build()
    }

    @GET
    @Path("/proposals/pending")
    @Authorize(action = "fraud.thresholds.read")
    @Operation(summary = "List threshold-set proposals awaiting a checker")
    suspend fun pending(): Response = Response.ok(activations.listPending().map { it.toView() }).build()

    @GET
    @Path("/active")
    @Authorize(action = "fraud.thresholds.read")
    @Operation(
        summary = "The threshold set the scoring engine uses now (404 when none is active — scoring then fails closed)",
    )
    suspend fun active(): Response {
        val active = activations.active() ?: return Response.status(HTTP_NOT_FOUND)
            .entity(mapOf("error" to "no fraud threshold set is active; amount rules are failing closed (REVIEW)"))
            .build()
        return Response.ok(ActiveThresholdsView(active.version, active.thresholds.toWire(), active.contentHash)).build()
    }
}

/** Amounts travel as decimal STRINGS, never JSON numbers, so no client float touches a money figure. */
data class ProposeThresholdsRequest(
    val version: Int? = null,
    val effectiveFrom: Instant? = null,
    val thresholds: Map<String, Map<String, String?>>? = null,
)

data class DecideThresholdsRequest(val approve: Boolean? = null, val reason: String? = null)

data class ActiveThresholdsView(
    val version: Int,
    val thresholds: Map<String, Map<String, String>>,
    val contentHash: String,
)

data class ThresholdProposalView(
    val id: UUID,
    val version: Int,
    val state: String,
    val thresholds: Map<String, Map<String, String>>,
    val contentHash: String,
    val effectiveFrom: Instant?,
    val activeFrom: Instant?,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
)

private fun Map<String, Map<String, BigDecimal>>.toWire() =
    mapValues { (_, byCcy) -> byCcy.mapValues { (_, amount) -> amount.stripTrailingZeros().toPlainString() } }

private fun ThresholdSetProposal.toView() = ThresholdProposalView(
    id = id,
    version = set.version,
    state = state.name,
    thresholds = set.thresholds.toWire(),
    contentHash = contentHash,
    effectiveFrom = effectiveFrom,
    activeFrom = activeFrom,
    proposedBy = proposedBy,
    proposedAt = proposedAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    decisionReason = decisionReason,
)

/** Plain decimals only — an exponent form (`1E+9`) is refused before any BigDecimal is built. */
private val PLAIN_DECIMAL = Regex("""^\d{1,16}(\.\d{1,4})?$""")

private const val HTTP_CREATED = 201
private const val HTTP_NOT_FOUND = 404
