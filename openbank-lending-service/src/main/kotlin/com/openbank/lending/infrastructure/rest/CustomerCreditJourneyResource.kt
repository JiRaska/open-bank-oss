// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.infrastructure.intake.CustomerIntakeConfig
import com.openbank.libs.authz.Authorize
import com.openbank.libs.lending.origination.CreditJourneyProjection
import com.openbank.libs.lending.origination.CreditJourneyView
import com.openbank.libs.lending.origination.CreditRequirement
import com.openbank.libs.lending.origination.OriginationState
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The customer-readable projection of an origination journey (ADR-0269 rule 3, ADR-0211 D1 states).
 *
 * This is the read half of the customer intake pair: `CustomerIntakeResource` files an application,
 * this route says where it got to. Without it the app's flow ends at submission, which is what it
 * did before this slice — a form into a void.
 *
 * ## What the projection deliberately does NOT carry
 *
 * No rate, no instalment, no APRC, no approval probability. Price is ADR-0269 rule 4 and arrives as
 * a server quote or a binding offer object, both of which are separate work (#6214). A number
 * invented here would be the exact defect the ADR exists to prevent — a customer plans a year
 * around an instalment.
 *
 * ## Caller and ownership
 *
 * Same shape as the intake POST: the edge's named principal is the only permitted caller, and the
 * party id comes from `X-Customer-Party-Id`, set by the edge from the customer JWT. A row whose
 * `partyId` does not match the header is not returned — a foreign application id must read as
 * "not found", never as someone else's credit history.
 */
@Path("/api/v1/lending/intake")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Customer self-service origination intake")
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class CustomerCreditJourneyResource(
    private val applications: LoanApplicationRepository,
    private val config: CustomerIntakeConfig,
    private val identity: SecurityIdentity,
) {
    @GET
    @Path("/applications")
    @Authorize(action = "lending.intake", resource = "")
    @Operation(summary = "The caller's own credit applications as customer-readable journeys (edge only)")
    fun list(@HeaderParam(CustomerIntakeResource.PARTY_HEADER) partyHeader: String?): Uni<Response> {
        val partyId = permittedParty(partyHeader) ?: return refusal()
        return applications.findByParty(partyId)
            .map { list -> Response.ok(list.map { it.toJourneyDto() }).build() }
    }

    @GET
    @Path("/applications/{id}")
    @Authorize(action = "lending.intake", resource = "")
    @Operation(summary = "One of the caller's own credit applications as a customer-readable journey")
    fun one(
        @HeaderParam(CustomerIntakeResource.PARTY_HEADER) partyHeader: String?,
        @PathParam("id") id: String,
    ): Uni<Response> {
        val partyId = permittedParty(partyHeader) ?: return refusal()
        val applicationId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return Uni.createFrom().item(notFound())
        return applications.findByParty(partyId).map { list ->
            // Filtered by owner, not fetched by id and then checked: a not-found and a
            // not-yours must be indistinguishable to the caller, or the id space leaks.
            list.firstOrNull { it.id.value == applicationId }
                ?.let { Response.ok(it.toJourneyDto()).build() }
                ?: notFound()
        }
    }

    /** The party id this caller may read, or null when the caller or the header is not permitted. */
    private fun permittedParty(partyHeader: String?): UUID? {
        if (!config.enabled) return null
        val permitted = config.callerPrincipal.orElse("")
        if (permitted.isBlank() || identity.principal?.name != permitted) return null
        val partyId = partyHeader?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return partyId.takeIf { it != ZERO_UUID }
    }

    private fun refusal(): Uni<Response> = Uni.createFrom().item(
        Response.status(HTTP_FORBIDDEN).entity(mapOf("error" to "caller is not the customer-edge intake principal"))
            .build(),
    )

    private fun notFound(): Response =
        Response.status(HTTP_NOT_FOUND).entity(mapOf("error" to "application not found")).build()

    companion object {
        private val ZERO_UUID = UUID(0, 0)
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
    }
}

/**
 * The wire shape. Flat and stable: the app renders it directly, so a rename here is a client break.
 *
 * [requirements] is empty in this slice — the requirement list is written by the steps that collect
 * documents and valuations, and none of those exist yet. Empty is the honest value: it says "we are
 * not waiting on you", which is true of every application in the current unsecured-only book.
 */
data class CustomerCreditJourneyDto(
    val id: String,
    val productKind: String,
    val state: String,
    val steps: List<CustomerCreditStepDto>,
    val requirements: List<CustomerCreditRequirementDto>,
    val awaitingCustomer: List<String>,
    val outcomeReasonCode: String?,
    val submittedAt: String,
)

data class CustomerCreditStepDto(val code: String, val status: String)

data class CustomerCreditRequirementDto(
    val code: String,
    val status: String,
    val completedByCustomer: Boolean,
    val reason: String?,
)

private fun LoanApplication.toJourneyDto(): CustomerCreditJourneyDto {
    val view: CreditJourneyView = CreditJourneyProjection.project(
        productKind = productKind,
        state = status,
        requirements = requirementsOf(this),
        outcomeReasonCode = decisionOutcomeReasonCode(),
    )
    return CustomerCreditJourneyDto(
        id = id.value.toString(),
        productKind = view.productKind.name,
        state = view.state.name,
        steps = view.steps.map { CustomerCreditStepDto(it.code, it.status.name) },
        requirements = view.requirements.map {
            CustomerCreditRequirementDto(it.code, it.status.name, it.completedByCustomer, it.reason)
        },
        awaitingCustomer = view.awaitingCustomer.map { it.code },
        outcomeReasonCode = view.outcomeReasonCode,
        submittedAt = createdAt.toInstant().toString(),
    )
}

/** No requirement collection exists yet — see [CustomerCreditJourneyDto]. */
private fun requirementsOf(@Suppress("UNUSED_PARAMETER") application: LoanApplication): List<CreditRequirement> =
    emptyList()

/**
 * The decision engine's reason codes, passed through only for a refused application.
 *
 * `decisionReasons` is the engine's own machine-readable list (ADR-0213); the free-text
 * `decisionReason` written by a human checker is deliberately NOT exposed — a customer-facing
 * refusal must not surface an officer's internal note verbatim.
 */
private fun LoanApplication.decisionOutcomeReasonCode(): String? =
    decisionReasons?.takeIf { it.isNotBlank() && status == OriginationState.DECLINED }
