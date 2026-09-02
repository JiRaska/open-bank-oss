// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.port.`in`.DomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentResult
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.ListDomesticPaymentsQuery
import com.openbank.domestic.application.port.`in`.PaymentConfirmationUseCase
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.rest.dto.CreateDomesticPaymentRequest
import com.openbank.domestic.infrastructure.rest.dto.TransitionDomesticPaymentStatusRequest
import com.openbank.domestic.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
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
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

@Path("/api/v1/domestic-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Domestic Payments", description = "Domestic CZK payment lifecycle")
class DomesticPaymentResource(
    private val paymentUseCase: DomesticPaymentUseCase,
    private val delegatedPaymentUseCase: DelegatedDomesticPaymentUseCase,
    private val confirmationUseCase: PaymentConfirmationUseCase,
) {

    // OIDC identity is taken from the CDI request-scoped SecurityIdentity, NOT the
    // JAX-RS @Context SecurityContext — consistent with how account-service resolves identity
    // in suspend resource methods (smallrye-context-propagation carries it across dispatches).
    @Inject
    lateinit var identity: SecurityIdentity

    private val actorId: UUID?
        get() = (identity.principal as? JsonWebToken)?.subject?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

    /** Issuer-qualified when a JWT supplies one, so equal subjects from different realms never share a key. */
    private val actorScope: String
        get() {
            val principal = identity.principal
            val jwt = principal as? JsonWebToken
            val issuer = jwt?.getClaim<Any>("iss")?.toString()?.trim()?.ifBlank { null }
            val subject = jwt?.subject?.trim()?.ifBlank { null }
                ?: principal.name.trim().ifBlank { error("Authenticated principal has no stable scope") }
            return listOfNotNull(issuer, subject).joinToString("\u001f")
        }

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "domestic-payment.create")
    @Operation(summary = "Create a domestic payment")
    suspend fun createPayment(
        request: CreateDomesticPaymentRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        // #3104 — the guard below could not run when the header was ABSENT: JAX-RS injected null,
        // and `null.isNotBlank()` threw NPE, so the very case it exists for answered 500. `suspend`
        // hid it further, since Kotlin emits no checkNotNullParameter intrinsic for a suspend
        // function — the null reached the first dereference instead of failing at the boundary.
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }

        // SyntheticTaintRequestFilter accepts this flag only after authenticating a configured
        // canary principal. Read its request property, never the caller's header or coroutine MDC,
        // then persist it through the outbox boundary for asynchronous consumers.
        val result = paymentUseCase.createPayment(
            request.toCommand(
                idempotencyKey = idempotencyKey,
                actorId = actorId,
                actorScope = actorScope,
                synthetic = requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true,
            ),
        )
        val responseBody = result.payment.toResponse()

        val response = Response.created(URI.create("/api/v1/domestic-payments/${result.payment.id}"))
            .entity(responseBody)
        if (result.replayed) response.header("X-Idempotency-Replayed", "true")
        return response.build()
    }

    /**
     * Workload-only delegated-create boundary.  The public owner route above deliberately never
     * accepts delegation context.  This route accepts it only from the exact customer-edge M2M
     * identity and passes the complete immutable tuple to the local binding state machine.
     */
    @POST
    @Path("/delegated")
    @RolesAllowed("ROLE_OPERATOR")
    @Authorize(action = "domestic-payment.create")
    @Operation(summary = "Create a delegated domestic payment from customer-edge")
    suspend fun createDelegatedPayment(
        request: CreateDomesticPaymentRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(CUSTOMER_PARTY_ID_HEADER) customerPartyIdHeader: String?,
        @HeaderParam(DELEGATION_ID_HEADER) delegationIdHeader: String?,
        @HeaderParam(DELEGATION_RESERVATION_ID_HEADER) reservationIdHeader: String?,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        require(identity.principal.name == CUSTOMER_EDGE_PRINCIPAL) {
            "Delegated payment creation is restricted to customer-edge"
        }
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        val customerPartyId = requiredUuid(CUSTOMER_PARTY_ID_HEADER, customerPartyIdHeader)
        val delegationId = requiredUuid(DELEGATION_ID_HEADER, delegationIdHeader)
        val reservationId = requiredUuid(DELEGATION_RESERVATION_ID_HEADER, reservationIdHeader)
        val result = delegatedPaymentUseCase.createDelegatedPayment(
            reservationId,
            request.toCommand(
                idempotencyKey = idempotencyKey,
                actorId = customerPartyId,
                actorScope = "$CUSTOMER_EDGE_PRINCIPAL:$customerPartyId",
                delegationId = delegationId,
                reservationId = reservationId,
                synthetic = requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true,
            ),
        )
        return when (result) {
            is DelegatedDomesticPaymentResult.Accepted -> {
                val response = Response.created(URI.create("/api/v1/domestic-payments/${result.result.payment.id}"))
                    .entity(result.result.payment.toResponse())
                if (result.result.replayed) response.header("X-Idempotency-Replayed", "true")
                response.build()
            }
            DelegatedDomesticPaymentResult.ReservationProjectionPending -> delegatedFailure(
                status = 425,
                code = "RESERVATION_PROJECTION_PENDING",
                message = "Delegated spend reservation is not ready; retry with the same idempotency key",
            )
            DelegatedDomesticPaymentResult.ReservationFinalizedAbsent -> delegatedFailure(
                status = 410,
                code = "RESERVATION_FINALIZED_ABSENT",
                message = "Delegated spend reservation was finalized without a payment",
            )
            is DelegatedDomesticPaymentResult.ReservationMismatch -> delegatedFailure(
                status = 409,
                code = "DELEGATED_RESERVATION_MISMATCH",
                message = result.reason,
            )
            DelegatedDomesticPaymentResult.AccountAuthorityUnavailable -> delegatedFailure(
                status = 503,
                code = "ACCOUNT_AUTHORITY_UNAVAILABLE",
                message = "Debit-account authority cannot be verified; retry with the same idempotency key",
            )
        }
    }

    @GET
    @Path("/{paymentId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "domestic-payment.read", resource = "#paymentId")
    @Operation(summary = "Get a domestic payment by ID")
    suspend fun getPayment(@PathParam("paymentId") paymentId: UUID): Response =
        Response.ok(paymentUseCase.getPayment(paymentId).toResponse()).build()

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
    @Authorize(action = "domestic-payment.list")
    @Operation(summary = "List domestic payments")
    suspend fun listPayments(
        @QueryParam("status") status: String?,
        @QueryParam("debtorAccountId") debtorAccountId: UUID?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response {
        val payments = paymentUseCase.listPayments(
            ListDomesticPaymentsQuery(
                status = status?.let(DomesticPaymentStatus::valueOf),
                debtorAccountId = debtorAccountId,
                limit = limit,
                offset = offset,
            ),
        )
        return Response.ok(payments.map { it.toResponse() }).build()
    }

    /**
     * Customer-facing "download confirmation" action (ADR-0248 #3). Strictly additive and
     * read-only: renders synchronously, on request only, off the payment's own persisted status
     * record — no pre-generation, no caching, nothing written here or in document-service. Only
     * meaningful once a payment has SETTLED (409 otherwise, mapped from [PaymentNotSettledMapper]).
     */
    @GET
    @Path("/{paymentId}/confirmation")
    @Produces(MediaType.TEXT_HTML)
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "domestic-payment.confirmation.read", resource = "#paymentId")
    @Operation(summary = "Download the payment confirmation document for a SETTLED payment")
    suspend fun getConfirmation(@PathParam("paymentId") paymentId: UUID, @QueryParam("lang") lang: String?): Response {
        val html = confirmationUseCase.getConfirmation(paymentId, lang)
        return Response.ok(html, MediaType.TEXT_HTML_TYPE).build()
    }

    @PATCH
    @Path("/{paymentId}/status")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    // Action namespace is the money-path scope (`domestic-payment.`, rules.yaml
    // money_path_services normalised) — NOT camelCase — so the base rest.rego
    // four_eyes rule can flag any future four-eyes verb (transfer/release/...)
    // on this rail. Renamed from `domesticPayment.transitionStatus` while the
    // service was still advisory-only (no behavioral change, ADR-0034 Phase 5).
    @Authorize(action = "domestic-payment.transitionStatus", resource = "#paymentId")
    @Operation(summary = "Transition domestic payment status")
    suspend fun transitionStatus(
        @PathParam("paymentId") paymentId: UUID,
        request: TransitionDomesticPaymentStatusRequest,
    ): Response {
        val payment = paymentUseCase.transitionStatus(request.toCommand(paymentId))
        return Response.ok(payment.toResponse()).build()
    }

    private fun requiredUuid(header: String, value: String?): UUID = try {
        UUID.fromString(requireNotNull(value?.trim()?.takeIf(String::isNotEmpty)) { "$header header is required" })
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$header header must be a UUID")
    }

    private fun delegatedFailure(status: Int, code: String, message: String): Response = Response.status(status)
        .entity(mapOf("status" to status, "code" to code, "error" to message))
        .build()

    private companion object {
        const val CUSTOMER_EDGE_PRINCIPAL = "service-account-openbank-edge"
        const val CUSTOMER_PARTY_ID_HEADER = "X-Customer-Party-Id"
        const val DELEGATION_ID_HEADER = "X-Delegation-Id"
        const val DELEGATION_RESERVATION_ID_HEADER = "X-Delegation-Reservation-Id"
    }
}
