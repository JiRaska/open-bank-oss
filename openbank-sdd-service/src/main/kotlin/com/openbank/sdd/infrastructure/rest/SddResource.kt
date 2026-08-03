// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sdd.application.port.`in`.AmendMandateCommand
import com.openbank.sdd.application.port.`in`.AmendMandateUseCase
import com.openbank.sdd.application.port.`in`.AssessRefundUseCase
import com.openbank.sdd.application.port.`in`.AuthoriseCollectionUseCase
import com.openbank.sdd.application.port.`in`.ConfirmMandateUseCase
import com.openbank.sdd.application.port.`in`.ListMandatesUseCase
import com.openbank.sdd.application.port.`in`.ManageMandateUseCase
import com.openbank.sdd.application.port.`in`.RegisterMandateCommand
import com.openbank.sdd.application.port.`in`.RegisterMandateUseCase
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.authorise.DebtorControls
import com.openbank.sdd.infrastructure.rest.dto.AmendMandateRequest
import com.openbank.sdd.infrastructure.rest.dto.AuthorisationResponse
import com.openbank.sdd.infrastructure.rest.dto.AuthoriseCollectionRequest
import com.openbank.sdd.infrastructure.rest.dto.MandateResponse
import com.openbank.sdd.infrastructure.rest.dto.RefundAssessmentResponse
import com.openbank.sdd.infrastructure.rest.dto.RegisterMandateRequest
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
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

@Path("/api/v1/sdd")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
    name = "SEPA Direct Debit",
    description = "Debtor-side SDD mandate vault, authorisation and refund assessment (ADR-0036)",
)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
class SddResource(
    private val register: RegisterMandateUseCase,
    private val confirm: ConfirmMandateUseCase,
    private val manage: ManageMandateUseCase,
    private val amend: AmendMandateUseCase,
    private val authorise: AuthoriseCollectionUseCase,
    private val refund: AssessRefundUseCase,
    private val list: ListMandatesUseCase,
    private val clock: Clock,
) {

    @POST
    @Path("/mandates")
    @Authorize(action = "sdd.create")
    @Operation(
        summary = "Register a debtor mandate (Core ⇒ ACTIVE, B2B ⇒ PENDING_CONFIRMATION). Idempotent on (CID, UMR).",
    )
    @WithTransaction
    fun registerMandate(req: RegisterMandateRequest): Uni<Response> = register.register(
        RegisterMandateCommand(
            accountId = req.accountId,
            debtorIban = req.debtorIban,
            creditorIdentifier = req.creditorIdentifier,
            umr = req.umr,
            scheme = req.scheme,
            sequenceType = req.sequenceType,
            creditorName = req.creditorName,
            debtorName = req.debtorName,
            signatureDate = req.signatureDate,
        ),
    ).map { Response.status(Response.Status.CREATED).entity(MandateResponse.of(it)).build() }

    @GET
    @Path("/mandates")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "sdd.list")
    @Operation(summary = "List an account's mandates")
    // #3104 — non-suspend, so an absent `accountId` threw at the method boundary and answered 500.
    fun listMandates(@QueryParam("accountId") accountId: UUID?): Uni<Response> =
        list.list(requireNotNull(accountId) { "query parameter 'accountId' is required" })
            .map { ms -> Response.ok(ms.map(MandateResponse::of)).build() }

    @GET
    @Path("/mandates/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "sdd.read", resource = "#id")
    @Operation(summary = "Fetch a single mandate")
    fun getMandate(@PathParam("id") id: UUID): Uni<Response> =
        list.get(id).map { Response.ok(MandateResponse.of(it)).build() }

    @POST
    @Path("/mandates/{id}/confirm")
    @Authorize(action = "sdd.approve", resource = "#id")
    @Operation(summary = "Confirm (verify) a B2B mandate — PENDING_CONFIRMATION ⇒ ACTIVE")
    @WithTransaction
    fun confirmMandate(@PathParam("id") id: UUID): Uni<Response> =
        confirm.confirm(id).map { Response.ok(MandateResponse.of(it)).build() }

    @POST
    @Path("/mandates/{id}/suspend")
    @Authorize(action = "sdd.update", resource = "#id")
    @Operation(summary = "Suspend an ACTIVE mandate")
    @WithTransaction
    fun suspendMandate(@PathParam("id") id: UUID): Uni<Response> =
        manage.suspend(id).map { Response.ok(MandateResponse.of(it)).build() }

    @POST
    @Path("/mandates/{id}/resume")
    @Authorize(action = "sdd.update", resource = "#id")
    @Operation(summary = "Resume a SUSPENDED mandate")
    @WithTransaction
    fun resumeMandate(@PathParam("id") id: UUID): Uni<Response> =
        manage.resume(id).map { Response.ok(MandateResponse.of(it)).build() }

    @POST
    @Path("/mandates/{id}/cancel")
    @Authorize(action = "sdd.delete", resource = "#id")
    @Operation(summary = "Cancel a mandate (terminal)")
    @WithTransaction
    fun cancelMandate(@PathParam("id") id: UUID): Uni<Response> =
        manage.cancel(id).map { Response.ok(MandateResponse.of(it)).build() }

    @PATCH
    @Path("/mandates/{id}")
    @Authorize(action = "sdd.update", resource = "#id")
    @Operation(summary = "Amend a mandate field (records an AMDT marker)")
    @WithTransaction
    fun amendMandate(@PathParam("id") id: UUID, req: AmendMandateRequest): Uni<Response> =
        amend.amend(id, AmendMandateCommand(req.field, req.newValue))
            .map { Response.ok(MandateResponse.of(it)).build() }

    @POST
    @Path("/collections/authorise")
    @Authorize(action = "sdd.authorise")
    @Operation(summary = "Fail-closed authorisation of an inbound collection (ACCEPT / REJECT / REFUSE)")
    @WithTransaction
    fun authoriseCollection(req: AuthoriseCollectionRequest): Uni<Response> = authorise.authorise(
        CollectionInstruction(
            creditorIdentifier = req.creditorIdentifier,
            umr = req.umr,
            scheme = req.scheme,
            sequenceType = req.sequenceType,
            amount = req.amount,
            currency = req.currency,
            dueDate = req.dueDate,
        ),
        DebtorControls(
            blockAll = req.controls.blockAll,
            blockedCreditors = req.controls.blockedCreditors,
            maxAmountPerCollection = req.controls.maxAmountPerCollection,
        ),
    ).map { Response.ok(AuthorisationResponse.of(it)).build() }

    @GET
    @Path("/mandates/{id}/refund-assessment")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "sdd.read", resource = "#id")
    @Operation(summary = "Assess a post-settlement refund claim (8-week unconditional / B2B none)")
    fun assessRefund(
        @PathParam("id") id: UUID,
        @QueryParam("debitDate") debitDate: String?,
        @QueryParam("asOf") asOf: String?,
    ): Uni<Response> {
        // #3104 — `asOf` was already optional; `debitDate` was not, and omitting it answered 500.
        // An UNPARSEABLE debitDate is a different class and already 400 (DateTimeExceptionMapper).
        requireNotNull(debitDate) { "query parameter 'debitDate' is required" }
        return refund
            .assessRefund(id, LocalDate.parse(debitDate), asOf?.let(LocalDate::parse) ?: LocalDate.now(clock))
            .map { Response.ok(RefundAssessmentResponse.of(it)).build() }
    }
}
