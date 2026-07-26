// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sepainstant.application.port.`in`.GetSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.`in`.RecallSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstCommand
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstPaymentUseCase
import com.openbank.sepainstant.infrastructure.rest.dto.RecallRequest
import com.openbank.sepainstant.infrastructure.rest.dto.SctInstPaymentResponse
import com.openbank.sepainstant.infrastructure.rest.dto.SubmitSctInstRequest
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Path("/api/v1/sepa-instant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "SCT Inst", description = "SEPA Instant Credit Transfer — sub-10s settlement")
class SctInstResource @Inject constructor(
    private val submitUseCase: SubmitSctInstPaymentUseCase,
    private val getUseCase: GetSctInstPaymentUseCase,
    private val recallUseCase: RecallSctInstPaymentUseCase,
) {

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "sctInstPayment.list")
    @Operation(summary = "List SCT Inst payments")
    fun listAll(): Uni<Response> = getUseCase.listAll().map { list ->
        Response.ok(list.map(::toResponse)).build()
    }

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sctInstPayment.create")
    @Operation(summary = "Submit SCT Inst payment")
    fun submit(@HeaderParam("Idempotency-Key") idempotencyKey: String?, body: SubmitSctInstRequest): Uni<Response> {
        val key = idempotencyKey ?: body.idempotencyKey
        val cmd = SubmitSctInstCommand(
            idempotencyKey = key,
            debtorAccountId = body.debtorAccountId,
            debtorIban = body.debtorIban,
            debtorName = body.debtorName,
            creditorIban = body.creditorIban,
            creditorName = body.creditorName,
            creditorBic = body.creditorBic,
            amount = body.amount,
            currency = body.currency,
            remittanceInfo = body.remittanceInfo,
            endToEndId = body.endToEndId,
        )
        return submitUseCase.submit(cmd).map { p ->
            Response.status(Response.Status.CREATED).entity(toResponse(p)).build()
        }
    }

    @GET
    @Path("/{paymentId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sctInstPayment.read", resource = "#paymentId")
    @Operation(summary = "Get SCT Inst payment by ID")
    fun getById(@PathParam("paymentId") paymentId: UUID): Uni<Response> =
        getUseCase.getById(paymentId).map { Response.ok(toResponse(it)).build() }

    @GET
    @Path("/debtor/{debtorAccountId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "sctInstPayment.list", resource = "#debtorAccountId")
    @Operation(summary = "List payments by debtor account")
    fun listByDebtor(
        @PathParam("debtorAccountId") debtorAccountId: UUID,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
    ): Uni<Response> = getUseCase.listByDebtor(debtorAccountId, page, size).map { list ->
        Response.ok(list.map(::toResponse)).build()
    }

    @POST
    @Path("/{paymentId}/recall")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sctInstPayment.recall", resource = "#paymentId")
    @Operation(summary = "Recall a settled SCT Inst payment")
    fun recall(@PathParam("paymentId") paymentId: UUID, body: RecallRequest): Uni<Response> =
        recallUseCase.recall(paymentId, body.reason).map { Response.ok(toResponse(it)).build() }

    private fun toResponse(p: com.openbank.sepainstant.domain.model.SctInstPayment) = SctInstPaymentResponse(
        paymentId = p.paymentId, status = p.status.name,
        debtorIban = p.debtorIban, creditorIban = p.creditorIban,
        amount = p.amount, currency = p.currency, endToEndId = p.endToEndId,
        executionTimeoutAt = p.executionTimeoutAt, settledAt = p.settledAt, createdAt = p.createdAt,
    )
}
