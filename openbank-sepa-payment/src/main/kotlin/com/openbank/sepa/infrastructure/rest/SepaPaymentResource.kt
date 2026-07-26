// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.sepa.application.port.`in`.HandlePaymentReturnCommand
import com.openbank.sepa.application.port.`in`.ListSepaPaymentsQuery
import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.infrastructure.rest.dto.CreateSepaPaymentRequest
import com.openbank.sepa.infrastructure.rest.dto.TransitionSepaPaymentStatusRequest
import com.openbank.sepa.infrastructure.rest.dto.toResponse
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
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
import java.net.URI
import java.util.UUID

@Path("/api/v1/sepa-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "SEPA Payments", description = "SEPA credit transfer lifecycle")
class SepaPaymentResource(
    private val paymentUseCase: SepaPaymentUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.create")
    @Operation(summary = "Create a SEPA payment")
    suspend fun createPayment(
        request: CreateSepaPaymentRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
    ): Response {
        require(idempotencyKey.isNotBlank()) { "Idempotency-Key header is required" }

        idempotencyStore.get(idempotencyKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val payment = paymentUseCase.createPayment(request.toCommand(idempotencyKey))
        val responseBody = payment.toResponse()
        idempotencyStore.save(idempotencyKey, 201, objectMapper.writeValueAsString(responseBody))

        return Response.created(URI.create("/api/v1/sepa-payments/${payment.id}"))
            .entity(responseBody)
            .build()
    }

    @GET
    @Path("/{paymentId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.read", resource = "#paymentId")
    @Operation(summary = "Get a SEPA payment by ID")
    suspend fun getPayment(@PathParam("paymentId") paymentId: UUID): Response =
        Response.ok(paymentUseCase.getPayment(paymentId).toResponse()).build()

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "sepaPayment.list")
    @Operation(summary = "List SEPA payments")
    suspend fun listPayments(
        @QueryParam("status") status: String?,
        @QueryParam("debtorAccountId") debtorAccountId: UUID?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response {
        val payments = paymentUseCase.listPayments(
            ListSepaPaymentsQuery(
                status = status?.let(SepaPaymentStatus::valueOf),
                debtorAccountId = debtorAccountId,
                limit = limit,
                offset = offset,
            ),
        )
        return Response.ok(payments.map { it.toResponse() }).build()
    }

    @PATCH
    @Path("/{paymentId}/status")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.transitionStatus", resource = "#paymentId")
    @Operation(summary = "Transition SEPA payment status")
    suspend fun transitionStatus(
        @PathParam("paymentId") paymentId: UUID,
        request: TransitionSepaPaymentStatusRequest,
    ): Response {
        val payment = paymentUseCase.transitionStatus(request.toCommand(paymentId))
        return Response.ok(payment.toResponse()).build()
    }

    @POST
    @Path("/returns")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "sepaPayment.handleReturn")
    @Operation(summary = "Handle inbound pacs.004 payment return from clearing")
    suspend fun handlePaymentReturn(pacs004Xml: String): Response {
        val payment = paymentUseCase.handlePaymentReturn(HandlePaymentReturnCommand(pacs004Xml))
        return Response.ok(payment.toResponse()).build()
    }
}
