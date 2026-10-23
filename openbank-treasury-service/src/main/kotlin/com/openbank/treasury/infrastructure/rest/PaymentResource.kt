// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.usecase.PaymentService
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.payment.PaymentStatus
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** ADR-0344 D6 / contract section 5: own-account payments. Release is the payment releaser's alone. */
@Tag(name = "Treasury", description = "Treasury payments (ADR-0344 D6)")
@Path("/api/v1/treasury/payments")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE, PAYMENT_RELEASER)
class PaymentResource {
    @Inject
    lateinit var payments: PaymentService

    @Inject
    lateinit var identity: SecurityIdentity

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private fun id(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw IllegalArgumentException("payment id must be a UUID") }

    private fun date(raw: String?, name: String): LocalDate = runCatching {
        LocalDate.parse(requireNotNull(raw) { "query parameter '$name' is required" })
    }.getOrElse { throw IllegalArgumentException("'$name' must be an ISO date (yyyy-MM-dd)", it) }

    @GET
    @Operation(summary = "Payments with a value date in from..to, optionally in one status")
    @Authorize(action = "treasury.payment.read")
    suspend fun list(
        @QueryParam("status") status: String?,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("limit") limit: Int?,
    ): List<PaymentResponse> {
        val l = limit ?: DEFAULT_LIMIT
        require(l in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        return payments.list(
            status?.let { s ->
                requireNotNull(
                    PaymentStatus.entries.firstOrNull {
                        it.name == s
                    },
                ) { "status must be one of ${PaymentStatus.entries}" }
            },
            date(from, "from"),
            date(to, "to"),
            l,
        ).map(PaymentResponse::from)
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "One payment")
    @Authorize(action = "treasury.payment.read")
    suspend fun get(@PathParam("id") id: String): PaymentResponse = PaymentResponse.from(payments.find(id(id)))

    @POST
    @Path("/propose")
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Propose payments for the FIXED flows inside the cut-off window of a date")
    @Authorize(action = "treasury.payment.propose")
    suspend fun propose(@QueryParam("date") date: String?): Map<String, Any?> {
        val r = payments.propose(date(date, "date"))
        return mapOf(
            "date" to r.date,
            "proposed" to r.proposed.map(PaymentResponse::from),
            "nettedToZero" to r.nettedToZero,
            "unaddressable" to r.unaddressable,
            "failures" to r.failures.size,
        )
    }

    @POST
    @Path("/{id}/release")
    @RolesAllowed(PAYMENT_RELEASER)
    @Operation(
        summary = "Release a PROPOSED payment to its rail (never a creator or term author, never AI)",
    )
    @Authorize(action = "treasury.payment.release")
    suspend fun release(@PathParam("id") id: String): PaymentResponse =
        PaymentResponse.from(payments.release(id(id), actor()))

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(BACK_OFFICE, APPROVER)
    @Operation(summary = "Cancel a PROPOSED payment; its flows can be proposed again (a released one is resolved)")
    @Authorize(action = "treasury.payment.cancel")
    suspend fun cancel(@PathParam("id") id: String, request: RejectRequest): PaymentResponse =
        PaymentResponse.from(payments.cancel(id(id), requireNotNull(request.reason) { "reason is required" }, actor()))

    @POST
    @Path("/{id}/resolve")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(
        summary = "Resolve a released payment MANUALLY_SETTLED or RETURNED (never its releaser or a term author)",
    )
    @Authorize(action = "treasury.payment.resolve")
    suspend fun resolve(@PathParam("id") id: String, request: ResolvePaymentRequest): PaymentResponse {
        val outcome = requireNotNull(
            request.outcome?.let { o ->
                listOf(PaymentStatus.MANUALLY_SETTLED, PaymentStatus.RETURNED).firstOrNull {
                    it.name ==
                        o
                }
            },
        ) { "outcome must be MANUALLY_SETTLED or RETURNED" }
        return PaymentResponse.from(
            payments.resolve(
                id(id),
                outcome,
                requireNotNull(request.reason) {
                    "reason is required"
                },
                actor(),
            ),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 5000
    }
}

data class ResolvePaymentRequest(val outcome: String? = null, val reason: String? = null)

data class PaymentResponse(
    val paymentId: UUID,
    val status: String,
    val direction: String,
    val counterpartyId: String,
    val currency: String,
    val amount: BigDecimal,
    val valueDate: LocalDate,
    val rail: String?,
    val ssi: Map<String, Any?>?,
    val nettedFlowIds: List<UUID>,
    val dealIds: List<UUID>,
    val idempotencyKey: String,
    val railReference: String?,
    val transferRail: String?,
    val releasedBy: String?,
    val statusReason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(p: Payment) = PaymentResponse(
            paymentId = p.paymentId,
            status = p.status.name,
            direction = p.direction.name,
            counterpartyId = p.counterpartyId,
            currency = p.currency,
            amount = p.amount,
            valueDate = p.valueDate,
            rail = p.ssi?.rail?.name,
            ssi = p.ssi?.let {
                mapOf(
                    "beneficiaryAccount" to it.beneficiaryAccount,
                    "bic" to it.bic,
                    "version" to it.ssiVersion,
                )
            },
            nettedFlowIds = p.nettedFlowIds,
            dealIds = p.dealIds,
            idempotencyKey = p.idempotencyKey,
            railReference = p.railReference,
            transferRail = p.transferRail?.name,
            releasedBy = p.releasedBy,
            statusReason = p.statusReason,
            createdAt = p.createdAt,
        )
    }
}
