// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentResult
import com.openbank.domestic.application.port.`in`.DelegatedDomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.DelegatedPaymentClaim
import com.openbank.domestic.infrastructure.rest.dto.CreateDelegatedDomesticPaymentRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import java.net.URI

/**
 * A separate resource from [DomesticPaymentResource] on purpose: the bound create uses a separate
 * port ([DelegatedDomesticPaymentUseCase]), so the public owner route cannot reach it by populating
 * fields, and nothing about the owner route's shape changes.
 */
@Path("/api/v1/domestic-payments/delegated")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Domestic Payments", description = "Domestic CZK payment lifecycle")
class DelegatedDomesticPaymentResource(private val delegatedPaymentUseCase: DelegatedDomesticPaymentUseCase) {

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * #16000 part 3 — create a delegated payment BOUND to its spend reservation, so that the
     * payment's terminal outcome (not the HTTP 2xx) settles the reservation.
     *
     * Identity is the verified customer-edge service account (`azp` + its own service-account
     * username, [ServiceAccountIdentity.isPrincipal]) and nothing else: no header or body field can
     * make a caller the edge. The binding tuple is read from the rail's projection of
     * delegation-service's reservation stream; the body only names the reservation and states what
     * the edge believes, which must agree with it.
     */
    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API")
    @Authorize(action = "domestic-payment.createDelegated")
    @Operation(summary = "Create a delegated domestic payment bound to its spend reservation (customer-edge only)")
    suspend fun createDelegatedPayment(
        request: CreateDelegatedDomesticPaymentRequest?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        if (!ServiceAccountIdentity.isPrincipal(identity, CUSTOMER_EDGE_PRINCIPAL)) {
            throw ForbiddenException("Delegated payment creation is restricted to customer-edge")
        }
        require(!idempotencyKey.isNullOrBlank() && idempotencyKey.length <= IDEMPOTENCY_KEY_MAX_LENGTH) {
            "Idempotency-Key must be between 1 and 128 characters"
        }
        val body = requireNotNull(request) { "A delegated payment request is required" }
        val reservationId = requireNotNull(body.reservationId) { "reservationId is required" }
        val claim = DelegatedPaymentClaim(
            delegationId = requireNotNull(body.delegationId) { "delegationId is required" },
            actingPartyId = requireNotNull(body.actingPartyId) { "actingPartyId is required" },
        )
        val command = body.toCommand(idempotencyKey, requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true)
        return when (val result = delegatedPaymentUseCase.createDelegatedPayment(reservationId, command, claim)) {
            is DelegatedDomesticPaymentResult.Accepted -> {
                val response = Response.created(URI.create("/api/v1/domestic-payments/${result.result.payment.id}"))
                    .entity(result.result.payment.forCaller(identity))
                if (result.result.replayed) response.header("X-Idempotency-Replayed", "true")
                response.build()
            }

            DelegatedDomesticPaymentResult.ReservationProjectionPending ->
                delegatedRefusal(
                    HTTP_CONFLICT,
                    RESERVATION_PENDING,
                    "The reservation is not known yet",
                    retryable = true,
                )

            DelegatedDomesticPaymentResult.ReservationFinalizedAbsent ->
                delegatedRefusal(HTTP_CONFLICT, RESERVATION_NOT_RESERVED, "The reservation is no longer reserved")

            is DelegatedDomesticPaymentResult.ReservationMismatch -> {
                // The precise field stays in the log: the caller learns only that the payment and
                // the reservation disagree, never which recorded value it guessed wrong.
                log.infof("Delegated payment refused for reservation %s: %s", reservationId, result.reason)
                delegatedRefusal(HTTP_CONFLICT, RESERVATION_MISMATCH, "The payment does not match the reservation")
            }

            DelegatedDomesticPaymentResult.AccountAuthorityUnavailable ->
                delegatedRefusal(
                    HTTP_UNAVAILABLE,
                    ACCOUNT_AUTHORITY_UNAVAILABLE,
                    "Account ownership could not be verified",
                    retryable = true,
                )
        }
    }

    private companion object {
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 128
        const val HTTP_CONFLICT = 409
        const val HTTP_UNAVAILABLE = 503
        const val RESERVATION_PENDING = "RESERVATION_PROJECTION_PENDING"
        const val RESERVATION_NOT_RESERVED = "RESERVATION_NOT_RESERVED"
        const val RESERVATION_MISMATCH = "RESERVATION_MISMATCH"
        const val ACCOUNT_AUTHORITY_UNAVAILABLE = "ACCOUNT_AUTHORITY_UNAVAILABLE"
        val log: Logger = Logger.getLogger(DelegatedDomesticPaymentResource::class.java)
    }
}

/** RFC 9457 refusal for the bound delegated create; a retryable one names when to retry. */
private fun delegatedRefusal(status: Int, code: String, detail: String, retryable: Boolean = false): Response {
    val builder = Response.status(status).type("application/problem+json").entity(
        mapOf(
            "type" to "urn:openbank:error:${code.lowercase().replace('_', '-')}",
            "title" to detail,
            "status" to status,
            "detail" to detail,
            "code" to code,
            "retryable" to retryable,
        ),
    )
    if (retryable) builder.header("Retry-After", DELEGATED_RETRY_AFTER_SECONDS)
    return builder.build()
}

private const val DELEGATED_RETRY_AFTER_SECONDS = "1"
