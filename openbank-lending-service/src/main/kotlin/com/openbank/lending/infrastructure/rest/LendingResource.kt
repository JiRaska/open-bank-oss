// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.ApplyForLoanUseCase
import com.openbank.lending.application.port.`in`.CollateralUseCase
import com.openbank.lending.application.port.`in`.DisburseLoanUseCase
import com.openbank.lending.application.port.`in`.ProvisioningUseCase
import com.openbank.lending.application.port.`in`.RescheduleLoanUseCase
import com.openbank.lending.application.port.`in`.ServicingUseCase
import com.openbank.lending.application.port.`in`.WriteOffLoanUseCase
import com.openbank.lending.domain.model.ApplicationStateSummary
import com.openbank.lending.domain.model.CollateralDecisionRequest
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanStateSummary
import com.openbank.lending.domain.model.RescheduleRequest
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Lending REST surface (ADR-0028 D5). Every endpoint is role-gated with raw string literals —
 * never `@PermitAll`. Origination decisions and collateral registration are four-eyes (enforced in
 * the application service; collateral gating added in the ADR-0028 follow-up, issue #621).
 */
@Path("/api/v1/lending")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Loan origination, servicing, collateral and IFRS 9 provisioning")
@RolesAllowed("ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK", "ROLE_COMPLIANCE", "ROLE_ADMIN")
@Suppress("LongParameterList")
class LendingResource(
    private val apply: ApplyForLoanUseCase,
    private val disburse: DisburseLoanUseCase,
    private val servicing: ServicingUseCase,
    private val writeOff: WriteOffLoanUseCase,
    private val reschedule: RescheduleLoanUseCase,
    private val collateral: CollateralUseCase,
    private val provisioning: ProvisioningUseCase,
    private val terminate: com.openbank.lending.application.port.`in`.TerminateLoanUseCase,
    private val outbox: com.openbank.lending.application.port.out.LendingOutboxRepository,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    /**
     * The trusted acting principal for maker-checker controls: the authenticated JWT subject, never a
     * client-supplied field. Blank/anonymous identities are rejected downstream by the application service.
     */
    private fun actor(): String = identity.principal?.name.orEmpty()

    // --- Origination --------------------------------------------------------------------------------

    @POST
    @Path("/applications")
    @Operation(summary = "Submit a loan application (maker)")
    @Authorize(action = "lending.create", resource = "")
    fun applyForLoan(request: LoanApplicationRequest): Uni<Response> = apply.apply(request, actor())
        .map { Response.status(HTTP_CREATED).entity(it).build() }
        .onFailure().recoverWithItem { e -> Response.status(400).entity(mapOf("error" to e.message)).build() }

    // --- Termination & early exit (ADR-0215) ---------------------------------------------------------

    @POST
    @Path("/loans/{id}/settlement-quote")
    @Operation(summary = "Issue a binding settlement quote for early repayment (ADR-0215 D2)")
    @Authorize(action = "lending.settlement.quote", resource = "#id")
    fun settlementQuote(@PathParam("id") id: UUID): Uni<Response> =
        terminate.requestSettlementQuote(com.openbank.libs.domain.identifiers.LoanId(id), actor())
            .map { Response.status(HTTP_CREATED).entity(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/settle")
    @Operation(summary = "Settle the loan against a still-valid quote (expired quotes are refused)")
    @Authorize(action = "lending.settlement.execute", resource = "#id")
    fun settle(@PathParam("id") id: UUID): Uni<Response> =
        terminate.settle(com.openbank.libs.domain.identifiers.LoanId(id), actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/withdrawal")
    @Operation(summary = "Statutory withdrawal inside the pack's cooling-off window (ADR-0215 D4)")
    @Authorize(action = "lending.withdrawal", resource = "#id")
    fun withdraw(@PathParam("id") id: UUID): Uni<Response> =
        terminate.withdraw(com.openbank.libs.domain.identifiers.LoanId(id), actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/mark-delinquent")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Operation(summary = "Mark the loan delinquent (oldest unpaid installment past due)")
    @Authorize(action = "lending.delinquency.mark", resource = "#id")
    fun markDelinquent(@PathParam("id") id: UUID): Uni<Response> =
        terminate.markDelinquent(com.openbank.libs.domain.identifiers.LoanId(id), actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/mark-defaulted")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Operation(summary = "Mark the loan defaulted (DPD crosses the pack's CRR Art. 178 threshold)")
    @Authorize(action = "lending.default.mark", resource = "#id")
    fun markDefaulted(@PathParam("id") id: UUID): Uni<Response> =
        terminate.markDefaulted(com.openbank.libs.domain.identifiers.LoanId(id), actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/forbearance")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Operation(summary = "Record the mandatory forbearance assessment (ADR-0215 D1)")
    @Authorize(action = "lending.forbearance.record", resource = "#id")
    fun forbearance(
        @PathParam("id") id: UUID,
        assessment: com.openbank.lending.domain.model.ForbearanceAssessment,
    ): Uni<Response> = terminate.recordForbearance(com.openbank.libs.domain.identifiers.LoanId(id), assessment, actor())
        .map { Response.ok(it).build() }
        .onFailure().recoverWithItem { e ->
            Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
        }

    @POST
    @Path("/loans/{id}/termination/propose")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Operation(summary = "Propose bank-initiated termination (maker; ground from the pinned pack)")
    @Authorize(action = "lending.termination.propose", resource = "#id")
    fun proposeTermination(@PathParam("id") id: UUID, request: TerminationProposalRequest): Uni<Response> =
        terminate.proposeTermination(com.openbank.libs.domain.identifiers.LoanId(id), request.ground, actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/termination/decide")
    @RolesAllowed("ROLE_COMPLIANCE", "ROLE_ADMIN")
    @Operation(summary = "Decide a termination proposal (checker; must differ from maker)")
    @Authorize(action = "lending.termination.decide", resource = "#id")
    fun decideTermination(@PathParam("id") id: UUID, request: DecideTerminationRequest): Uni<Response> =
        terminate.decideTermination(com.openbank.libs.domain.identifiers.LoanId(id), request.approve, actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @POST
    @Path("/loans/{id}/accelerate")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Operation(summary = "Accelerate the loan after the notice period elapses (ADR-0215 D1)")
    @Authorize(action = "lending.acceleration.execute", resource = "#id")
    fun accelerate(@PathParam("id") id: UUID): Uni<Response> =
        terminate.accelerate(com.openbank.libs.domain.identifiers.LoanId(id), actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e ->
                Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build()
            }

    @GET
    @Path("/applications/{id}/evidence")
    @RolesAllowed("ROLE_COMPLIANCE", "ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Authorize(action = "lending.evidence.read", resource = "#id")
    @Operation(summary = "Ordered evidence bundle for one loan application (ADR-0214)")
    suspend fun evidence(@PathParam("id") id: UUID): Response {
        val events = outbox.findByAggregateId(id).map { entry ->
            mapOf(
                "eventId" to entry.eventId.toString(),
                "eventType" to entry.eventType,
                "occurredAt" to entry.createdAt.toString(),
                "payload" to entry.payload,
            )
        }
        return Response.ok(
            mapOf(
                "applicationId" to id.toString(),
                "attestation" to "local-outbox",
                "eventCount" to events.size,
                "events" to events,
                "requestedBy" to actor(),
            ),
        ).build()
    }

    @POST
    @Path("/applications/{id}/advance")
    @Operation(summary = "Advance an application one step along the origination graph (ADR-0211)")
    @Authorize(action = "lending.advance", resource = "#id")
    fun advanceApplication(@PathParam("id") id: UUID): Uni<Response> = apply.advance(LoanApplicationId(id), actor())
        .map { Response.ok(it).build() }
        .onFailure(IllegalArgumentException::class.java)
        .recoverWithItem { e -> Response.status(HTTP_NOT_FOUND).entity(mapOf("error" to e.message)).build() }
        .onFailure(IllegalStateException::class.java)
        .recoverWithItem { e -> Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build() }

    @POST
    @Path("/applications/{id}/decision")
    @Operation(summary = "Approve or reject an application (checker; must differ from maker)")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Authorize(action = "lending.approve", resource = "#id")
    fun decide(@PathParam("id") id: UUID, decision: DecisionRequest): Uni<Response> =
        apply.decide(LoanApplicationId(id), decision, actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(409).entity(mapOf("error" to e.message)).build() }

    @GET
    @Path("/applications/{id}")
    @Operation(summary = "Get a loan application")
    @Authorize(action = "lending.read", resource = "#id")
    fun getApplication(@PathParam("id") id: UUID): Uni<Response> = apply.getApplication(LoanApplicationId(id))
        .map { it?.let { a -> Response.ok(a).build() } ?: Response.status(404).build() }

    @GET
    @Path("/applications")
    @Operation(summary = "List a party's loan applications")
    @Authorize(action = "lending.list", resource = "")
    // #3104 — non-suspend, so an absent `partyId` threw Intrinsics.checkNotNullParameter at the
    // method boundary and answered 500. Nullable + requireNotNull is the only shape in which a
    // guard runs at all; requireNotNull returns the value, so the expression body survives.
    fun listApplications(@QueryParam("partyId") partyId: UUID?) =
        apply.listApplications(requireNotNull(partyId) { "query parameter 'partyId' is required" })

    @GET
    @Path("/applications/recent")
    @Operation(summary = "Backoffice queue: newest applications fleet-wide, optionally one status (ADR-0230)")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK")
    @Authorize(action = "lending.list", resource = "")
    fun listRecentApplications(
        @QueryParam("status") status: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ) = apply.listRecentApplications(status, limit)

    @GET
    @Path("/applications/summary")
    @Operation(
        summary = "Per-state application totals across the whole book (issue #3294)",
        description = "The answer /applications/recent cannot give: that endpoint is a capped list " +
            "(1..100), so a count taken from it is 'of the newest N', never the book. Aggregated in " +
            "the database. Money totals are per CURRENCY — loan_application.currency is per row, so " +
            "one summed number would be CZK added to EUR.",
    )
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_LENDING_OFFICER", "ROLE_CREDIT_RISK")
    @Authorize(action = "lending.list", resource = "")
    fun summariseApplications(): Uni<List<ApplicationStateSummary>> = apply.summariseApplications()

    @GET
    @Path("/loans/summary")
    @Operation(
        summary = "Per-status loan-book totals (issue #3294)",
        description = "Companion to /applications/summary for the loan book. Totals are per currency.",
    )
    @RolesAllowed(
        "ROLE_VIEWER",
        "ROLE_OPERATOR",
        "ROLE_LENDING_OFFICER",
        "ROLE_CREDIT_RISK",
        "ROLE_COMPLIANCE",
        "ROLE_ADMIN",
    )
    @Authorize(action = "lending.list", resource = "")
    fun summariseLoans(): Uni<List<LoanStateSummary>> = servicing.summariseLoans()

    @POST
    @Path("/applications/{id}/disburse")
    @Operation(summary = "Disburse an approved application, booking the loan and its schedule")
    @RolesAllowed("ROLE_LENDING_OFFICER", "ROLE_ADMIN")
    @Authorize(action = "lending.disburse", resource = "#id")
    fun disburseLoan(@PathParam("id") id: UUID): Uni<Response> = disburse.disburse(LoanApplicationId(id), actor())
        .map { Response.status(HTTP_CREATED).entity(it).build() }
        .onFailure().recoverWithItem { e -> Response.status(409).entity(mapOf("error" to e.message)).build() }

    // --- Servicing ----------------------------------------------------------------------------------

    @GET
    @Path("/loans/{id}")
    @Operation(summary = "Get a loan")
    // Read endpoint: widen past the class-level staff-only roles so the customer-edge (ROLE_OPERATOR)
    // can serve a customer their own loan. OPA (lending.read + operator-read-any / customer scoping)
    // and the edge's per-caller partyId ownership check remain the fine-grained gate.
    @RolesAllowed(
        "ROLE_VIEWER",
        "ROLE_OPERATOR",
        "ROLE_LENDING_OFFICER",
        "ROLE_CREDIT_RISK",
        "ROLE_COMPLIANCE",
        "ROLE_ADMIN",
    )
    @Authorize(action = "lending.read", resource = "#id")
    fun getLoan(@PathParam("id") id: UUID): Uni<Response> =
        servicing.getLoan(LoanId(id)).map { it?.let { l -> Response.ok(l).build() } ?: Response.status(404).build() }

    @GET
    @Path("/loans/{id}/schedule")
    @Operation(summary = "Get a loan's repayment schedule")
    @RolesAllowed(
        "ROLE_VIEWER",
        "ROLE_OPERATOR",
        "ROLE_LENDING_OFFICER",
        "ROLE_CREDIT_RISK",
        "ROLE_COMPLIANCE",
        "ROLE_ADMIN",
    )
    @Authorize(action = "lending.read", resource = "#id")
    fun getSchedule(@PathParam("id") id: UUID) = servicing.getSchedule(LoanId(id))

    @GET
    @Path("/loans")
    @Operation(summary = "List a party's loans")
    @RolesAllowed(
        "ROLE_VIEWER",
        "ROLE_OPERATOR",
        "ROLE_LENDING_OFFICER",
        "ROLE_CREDIT_RISK",
        "ROLE_COMPLIANCE",
        "ROLE_ADMIN",
    )
    @Authorize(action = "lending.list", resource = "")
    // #3104 — see listApplications above.
    fun listLoans(@QueryParam("partyId") partyId: UUID?) =
        servicing.listLoans(requireNotNull(partyId) { "query parameter 'partyId' is required" })

    @GET
    @Path("/loans/active")
    @Operation(summary = "Backoffice portfolio: active loans fleet-wide (ADR-0230)")
    @RolesAllowed(
        "ROLE_VIEWER",
        "ROLE_OPERATOR",
        "ROLE_LENDING_OFFICER",
        "ROLE_CREDIT_RISK",
        "ROLE_COMPLIANCE",
        "ROLE_ADMIN",
    )
    @Authorize(action = "lending.list", resource = "")
    fun listActiveLoans(@QueryParam("limit") @DefaultValue("50") limit: Int) = servicing.listActiveLoans(limit)

    @POST
    @Path("/loans/{id}/installments/{installmentId}/repay")
    @Operation(summary = "Record a repayment against an installment")
    @Authorize(action = "lending.repay", resource = "#id")
    fun repay(@PathParam("id") id: UUID, @PathParam("installmentId") installmentId: UUID): Uni<Response> =
        servicing.recordRepayment(LoanId(id), installmentId)
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(409).entity(mapOf("error" to e.message)).build() }

    @POST
    @Path("/loans/{id}/writeoff")
    @Operation(summary = "Write off an uncollectible loan's remaining exposure (credit-risk/compliance)")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_COMPLIANCE", "ROLE_ADMIN")
    @Authorize(action = "lending.writeoff", resource = "#id")
    fun writeOffLoan(@PathParam("id") id: UUID, request: WriteOffRequest): Uni<Response> =
        writeOff.writeOff(LoanId(id), request)
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(409).entity(mapOf("error" to e.message)).build() }

    @POST
    @Path("/loans/{id}/reschedule")
    @Operation(summary = "Restructure a loan: replace its remaining schedule, optionally forgiving principal")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_COMPLIANCE", "ROLE_ADMIN")
    @Authorize(action = "lending.reschedule", resource = "#id")
    fun rescheduleLoan(@PathParam("id") id: UUID, request: RescheduleRequest): Uni<Response> =
        reschedule.reschedule(LoanId(id), request, actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(409).entity(mapOf("error" to e.message)).build() }

    // --- Collateral (four-eyes: register is the maker, decide is a different checker) ---------------

    @POST
    @Path("/loans/{id}/collateral")
    @Operation(summary = "Register collateral against a loan (maker; PENDING until a checker approves it)")
    @Authorize(action = "lending.collateralRegister", resource = "#id")
    fun registerCollateral(@PathParam("id") id: UUID, request: CollateralRequest): Uni<Response> =
        collateral.register(LoanId(id), request, actor())
            .map { Response.status(HTTP_CREATED).entity(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(400).entity(mapOf("error" to e.message)).build() }

    @POST
    @Path("/collateral/{id}/decision")
    @Operation(summary = "Approve or reject a pending collateral registration (checker; must differ from registrant)")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_ADMIN")
    @Authorize(action = "lending.collateralDecide", resource = "#id")
    fun decideCollateral(@PathParam("id") id: UUID, decision: CollateralDecisionRequest): Uni<Response> =
        collateral.decide(CollateralId(id), decision, actor())
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(409).entity(mapOf("error" to e.message)).build() }

    @GET
    @Path("/loans/{id}/collateral")
    @Operation(summary = "List collateral registered against a loan (any status)")
    @Authorize(action = "lending.read", resource = "#id")
    fun listCollateral(@PathParam("id") id: UUID) = collateral.list(LoanId(id))

    // --- Provisioning (IFRS 9) ----------------------------------------------------------------------

    @GET
    @Path("/loans/{id}/provisioning")
    @Operation(summary = "IFRS 9 staging + ECL snapshot for a loan")
    @RolesAllowed("ROLE_CREDIT_RISK", "ROLE_COMPLIANCE", "ROLE_ADMIN")
    @Authorize(action = "lending.read", resource = "#id")
    fun provisioning(@PathParam("id") id: UUID, @QueryParam("asOf") asOf: String?): Uni<Response> =
        provisioning.assess(LoanId(id), asOf?.let { LocalDate.parse(it) } ?: LocalDate.now(clock))
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.status(404).entity(mapOf("error" to e.message)).build() }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_NOT_FOUND = 404
        const val HTTP_UNPROCESSABLE = 422
    }
}

data class TerminationProposalRequest(val ground: String)

data class DecideTerminationRequest(val approve: Boolean)
