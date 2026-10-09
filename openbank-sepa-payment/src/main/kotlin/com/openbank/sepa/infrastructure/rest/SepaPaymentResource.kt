// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.idempotency.ReserveResult
import com.openbank.libs.security.actorName
import com.openbank.libs.security.actorType
import com.openbank.libs.web.ApiVersionResponseFilter
import com.openbank.sepa.application.port.`in`.HandlePaymentReturnCommand
import com.openbank.sepa.application.port.`in`.ListSepaPaymentsQuery
import com.openbank.sepa.application.port.`in`.PaymentConfirmationUseCase
import com.openbank.sepa.application.port.`in`.SepaPaymentUseCase
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.infrastructure.rest.dto.CreateSepaPaymentRequest
import com.openbank.sepa.infrastructure.rest.dto.SepaReceiptLookupRequest
import com.openbank.sepa.infrastructure.rest.dto.SepaReceiptLookupResponse
import com.openbank.sepa.infrastructure.rest.dto.TransitionSepaPaymentStatusRequest
import com.openbank.sepa.infrastructure.rest.dto.toResponse
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
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

private const val CREATE_PATH = "/api/v1/sepa-payments"
private const val IDEMPOTENCY_SERVICE = "sepa-payment"
private const val EDGE_PRINCIPAL = "service-account-openbank-edge"
private val log = Logger.getLogger(SepaPaymentResource::class.java)

@Path("/api/v1/sepa-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "SEPA Payments", description = "SEPA credit transfer lifecycle")
class SepaPaymentResource(
    private val paymentUseCase: SepaPaymentUseCase,
    private val confirmationUseCase: PaymentConfirmationUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    // Field-injected, request-scoped: the caller's identity scopes its Idempotency-Key.
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    // #10486: ROLE_API admitted so standing-order-service's own identity (ROLE_API only) reaches
    // OPA; sepa_payment_rest_ext.rego grants it sepaPayment.create by principal.id and denies every
    // other ROLE_API holder.
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.create")
    @Operation(summary = "Create a SEPA payment")
    suspend fun createPayment(
        request: CreateSepaPaymentRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-Customer-Party-Id") customerPartyId: String?,
        @HeaderParam("X-Customer-Actor-Id") customerActorPartyId: String?,
    ): Response {
        // #3104 — an ABSENT header injected null, so `null.isNotBlank()` threw NPE and this guard
        // answered 500 in exactly the case it was written for. A blank header was always a 400.
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }

        // #10916: the key is bound to this request's fingerprint and claimed ATOMICALLY before any
        // side effect runs — a different body under the same key answers 409 IDEMPOTENCY_KEY_REUSED,
        // a concurrent duplicate answers 409 IDEMPOTENCY_REQUEST_IN_PROGRESS.
        // The Redis reservation is scoped by creator (and edge party); the durable row separately
        // verifies the same provenance before any replay is returned.
        val partyId = trustedEdgeUuid(customerPartyId, "X-Customer-Party-Id")
        val actorPartyId = trustedEdgeUuid(customerActorPartyId, "X-Customer-Actor-Id")
        val scope = IdempotencyScope(IDEMPOTENCY_SERVICE, callerScope(partyId, actorPartyId))
        val requestHash = RequestFingerprints.of(objectMapper, "POST", CREATE_PATH, request)
        // #11642: Money is built here, BEFORE the key is reserved — an amount or currency it cannot
        // hold is a 400 (kernel InvalidMoneyException: AMOUNT_SCALE_EXCEEDED / CURRENCY_UNSUPPORTED) that leaves no idempotency
        // record, row, outbox event or downstream call behind. #11931: the same step refuses a
        // non-EUR currency (SCT is euro-only) as 400 CURRENCY_NOT_ALLOWED, equally before the key.
        val command = request.toCommand(idempotencyKey, requestHash).copy(
            initiatingPrincipal = identity.principal.name,
            initiatingPartyId = partyId,
            initiatingActorPartyId = actorPartyId,
        )
        when (
            val reservation = idempotencyStore.reserve(
                scope,
                idempotencyKey,
                requestHash,
                IdempotencyStore.DEFAULT_IN_FLIGHT_TTL_SECONDS,
            )
        ) {
            is ReserveResult.Replay -> {
                // Redis binds the payload, but its saved response can lag later payment transitions.
                // Read the durable row and recheck its original caller before returning a receipt.
                val current = paymentUseCase.findReceipt(
                    idempotencyKey,
                    request.debtorAccountId,
                    identity.principal.name,
                    partyId,
                    actorPartyId,
                ) ?: throw IdempotencyRequestInProgressException()
                if (current.requestHash != requestHash) throw IdempotencyKeyReusedException()
                return Response.created(URI.create("/api/v1/sepa-payments/${current.id}"))
                    .entity(current.toResponse())
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
            ReserveResult.Mismatch -> throw IdempotencyKeyReusedException()
            ReserveResult.InFlight -> throw IdempotencyRequestInProgressException()
            ReserveResult.Reserved -> Unit
        }

        var created = false
        val payment = try {
            paymentUseCase.createPayment(command).also { created = true }
        } finally {
            // Any failure (the exception propagates unchanged) frees the in-flight marker so a
            // retry of the same request can run instead of answering IN_PROGRESS for 5 minutes.
            // The release itself runs shielded from cancellation and never rethrows — a failure to
            // release must not mask the original exception that made release necessary, and a
            // cancelled caller must not abandon the release mid-flight and leave the key stuck
            // IN_PROGRESS for its full TTL.
            if (!created) {
                withContext(NonCancellable) {
                    runCatching { idempotencyStore.release(scope, idempotencyKey, requestHash) }
                        .onFailure { log.warn("Failed to release idempotency key after create failure", it) }
                }
            }
        }
        val responseBody = payment.toResponse()
        idempotencyStore.save(
            scope,
            idempotencyKey,
            requestHash,
            201,
            objectMapper.writeValueAsString(responseBody),
            IdempotencyStore.DEFAULT_RECORD_TTL_SECONDS,
        )

        return Response.created(URI.create("/api/v1/sepa-payments/${payment.id}"))
            .entity(responseBody)
            .build()
    }

    @POST
    @Path("/receipts/lookup")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.lookupReceipt")
    @Operation(summary = "Resolve a SEPA create receipt for the authenticated caller")
    suspend fun lookupReceipt(
        request: SepaReceiptLookupRequest?,
        @HeaderParam("X-Customer-Party-Id") customerPartyId: String?,
        @HeaderParam("X-Customer-Actor-Id") customerActorPartyId: String?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        require(request.idempotencyKey.isNotBlank()) { "idempotencyKey is required" }
        val partyId = trustedEdgeUuid(customerPartyId, "X-Customer-Party-Id")
        val actorPartyId = trustedEdgeUuid(customerActorPartyId, "X-Customer-Actor-Id")
        val payment = paymentUseCase.findReceipt(
            request.idempotencyKey,
            request.debtorAccountId,
            identity.principal.name,
            partyId,
            actorPartyId,
        )
        val result = if (payment == null) {
            SepaReceiptLookupResponse("UNKNOWN")
        } else {
            SepaReceiptLookupResponse("FOUND", payment.id, payment.status)
        }
        return Response.ok(result).build()
    }

    private fun callerScope(partyId: UUID?, actorPartyId: UUID?): String =
        if (partyId == null) identity.principal.name else "${identity.principal.name}:$partyId:$actorPartyId"

    private fun trustedEdgeUuid(header: String?, name: String): UUID? {
        if (identity.principal.name != EDGE_PRINCIPAL) return null
        require(!header.isNullOrBlank()) { "$name is required for customer-edge" }
        return runCatching { UUID.fromString(header) }
            .getOrElse { throw IllegalArgumentException("$name must be a UUID") }
    }

    @GET
    @Path("/{paymentId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.read", resource = "#paymentId")
    @Operation(summary = "Get a SEPA payment by ID")
    suspend fun getPayment(@PathParam("paymentId") paymentId: UUID): Response =
        Response.ok(paymentUseCase.getPayment(paymentId).toResponse()).build()

    @GET
    @Path("/{paymentId}/confirmation")
    @Produces("text/html")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sepaPayment.downloadConfirmation", resource = "#paymentId")
    @Operation(summary = "Download the payment confirmation document for a COMPLETED SEPA payment")
    suspend fun getConfirmation(
        @PathParam("paymentId") paymentId: UUID,
        @QueryParam("locale") @DefaultValue("en") locale: String,
    ): Response {
        // ADR-0248 #3: rendered synchronously, on this explicit customer request only — never
        // pre-generated, never cached, never persisted anywhere (in this service or document-service).
        val confirmation = confirmationUseCase.getConfirmation(paymentId, locale)
        return Response.ok(confirmation.bytes)
            .type(confirmation.contentType)
            .header("Content-Disposition", "attachment; filename=\"${confirmation.fileName}\"")
            .build()
    }

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
    suspend fun handlePaymentReturn(
        pacs004Xml: String,
        @Context securityContext: SecurityContext,
        @Context requestContext: ContainerRequestContext,
    ): Response {
        // Issue #6056. The actor is taken from the SECURITY CONTEXT and the correlation id from the
        // property `CorrelationIdRequestFilter` set on this request — never from the pacs.004 body.
        // The whole point of the record is that the party whose action is in dispute does not get
        // to write the part of it that names them.
        //
        // `correlationId` is read from the request property rather than the MDC accessor in
        // libs-security: this handler is `suspend`, and MDC is not guaranteed to survive the
        // dispatch onto a coroutine, whereas the request property is on the request itself. It is
        // the same value the response's `X-Correlation-ID` header carries.
        val payment = paymentUseCase.handlePaymentReturn(
            HandlePaymentReturnCommand(
                pacs004Xml = pacs004Xml,
                actorId = securityContext.actorName,
                actorType = securityContext.actorType,
                correlationId = requestContext.getProperty(ApiVersionResponseFilter.CORRELATION_ID_KEY)?.toString(),
            ),
        )
        return Response.ok(payment.toResponse()).build()
    }
}
