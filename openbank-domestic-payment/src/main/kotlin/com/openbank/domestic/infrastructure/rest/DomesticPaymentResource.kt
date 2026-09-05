// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.port.`in`.DomesticPaymentUseCase
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
                idempotencyKey,
                actorId,
                actorScope,
                requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true,
            ),
        )
        val responseBody = result.payment.toResponse()

        val response = Response.created(URI.create("/api/v1/domestic-payments/${result.payment.id}"))
            .entity(responseBody)
        if (result.replayed) response.header("X-Idempotency-Replayed", "true")
        return response.build()
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
}
