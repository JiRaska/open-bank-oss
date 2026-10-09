// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.Money
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.idempotency.ReserveResult
import com.openbank.sepainstant.application.port.`in`.GetSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.`in`.RecallSctInstPaymentUseCase
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstCommand
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstPaymentUseCase
import com.openbank.sepainstant.domain.error.SctInstSchemeRules
import com.openbank.sepainstant.infrastructure.rest.dto.RecallRequest
import com.openbank.sepainstant.infrastructure.rest.dto.SctInstPaymentResponse
import com.openbank.sepainstant.infrastructure.rest.dto.SctInstReceiptLookupRequest
import com.openbank.sepainstant.infrastructure.rest.dto.SctInstReceiptLookupResponse
import com.openbank.sepainstant.infrastructure.rest.dto.SubmitSctInstRequest
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
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
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
    private val identity: SecurityIdentity,
) {
    private val actorScope: String
        get() {
            val jwt = identity.principal as? JsonWebToken
            val issuer = jwt?.getClaim<Any>("iss")?.toString()?.trim()?.ifBlank { null }
            val subject = jwt?.subject?.trim()?.ifBlank { null }
                ?: identity.principal.name.trim().ifBlank { error("Authenticated principal has no stable scope") }
            return listOfNotNull(issuer, subject).joinToString("\u001f")
        }

    private fun trustedCustomerProvenance(partyHeader: String?, actorHeader: String?): Pair<UUID?, UUID?> {
        val jwt = identity.principal as? JsonWebToken
        val edge = jwt?.getClaim<String>("preferred_username") == "service-account-openbank-edge" &&
            jwt.getClaim<String>("azp") == "openbank-edge"
        if (partyHeader == null && actorHeader == null && !edge) return null to null
        if (!edge || partyHeader.isNullOrBlank() || actorHeader.isNullOrBlank() || '\u001f' !in actorScope) {
            throw ForbiddenException("Customer provenance requires the authenticated customer edge")
        }
        val party = runCatching { UUID.fromString(partyHeader) }.getOrNull()
        val actor = runCatching { UUID.fromString(actorHeader) }.getOrNull()
        if (party == null || actor == null) throw ForbiddenException("Customer provenance is invalid")
        return party to actor
    }

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
    suspend fun submit(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-Customer-Party-Id") customerPartyHeader: String?,
        @HeaderParam("X-Customer-Actor-Id") customerActorHeader: String?,
        body: SubmitSctInstRequest?,
    ): Response {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(body) { "request body is required" }
        val key = idempotencyKey ?: body.idempotencyKey
        require(key.isNotBlank()) { "Idempotency-Key is required" }
        val (partyId, actorId) = trustedCustomerProvenance(customerPartyHeader, customerActorHeader)
        // #11604: Money is built here, BEFORE the use case looks the idempotency key up — an amount
        // or currency it cannot hold is a 400 (kernel InvalidMoneyException: AMOUNT_SCALE_EXCEEDED /
        // CURRENCY_UNSUPPORTED / VALIDATION_ERROR) that leaves no row, event, screening call or
        // downstream call behind.
        val amount = Money.parseInbound(body.amount, body.currency)
        // #11913: SCT Inst is a euro-only scheme. A valid ISO currency other than EUR is refused here,
        // still before the key is looked up — 400 CURRENCY_NOT_ALLOWED, nothing persisted or screened.
        SctInstSchemeRules.requireSchemeCurrency(amount)
        val requestHash = RequestFingerprints.of(
            objectMapper,
            "POST",
            "/api/v1/sepa-instant",
            mapOf(
                "request" to body.copy(idempotencyKey = key),
                "principal" to actorScope,
                "partyId" to partyId,
                "actorId" to actorId,
            ),
        )
        val cmd = SubmitSctInstCommand(
            idempotencyKey = key,
            debtorAccountId = body.debtorAccountId,
            debtorIban = body.debtorIban,
            debtorName = body.debtorName,
            creditorIban = body.creditorIban,
            creditorName = body.creditorName,
            creditorBic = body.creditorBic,
            amount = amount,
            remittanceInfo = body.remittanceInfo,
            endToEndId = body.endToEndId,
            requestHash = requestHash,
            initiatingPrincipal = actorScope,
            initiatingPartyId = partyId,
            initiatingActorPartyId = actorId,
        )
        // The SQL idempotency key is global. Use the same scope for every caller so even
        // cross-actor races contend before screening or scheme submission.
        val scope = IdempotencyScope("sepa-instant", "global")
        when (
            val reservation = idempotencyStore.reserve(
                scope,
                key,
                requestHash,
                IdempotencyStore.DEFAULT_IN_FLIGHT_TTL_SECONDS,
            )
        ) {
            is ReserveResult.Replay -> {
                // Redis only proves this key and payload were seen. Its cached body can be stale
                // after screening, settlement or recall; SQL is the current receipt authority.
                val current = submitUseCase.findReceipt(
                    key,
                    body.debtorAccountId,
                    actorScope,
                    partyId,
                    actorId,
                ).awaitSuspending() ?: throw IdempotencyRequestInProgressException()
                return Response.status(Response.Status.CREATED).entity(toResponse(current))
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true").build()
            }
            ReserveResult.Mismatch -> throw IdempotencyKeyReusedException()
            ReserveResult.InFlight -> throw IdempotencyRequestInProgressException()
            ReserveResult.Reserved -> Unit
        }
        val payment = try {
            submitUseCase.submit(cmd).awaitSuspending()
        } catch (failure: Throwable) {
            idempotencyStore.release(scope, key, requestHash)
            throw failure
        }
        val response = toResponse(payment)
        idempotencyStore.save(
            scope,
            key,
            requestHash,
            201,
            objectMapper.writeValueAsString(response),
            IdempotencyStore.DEFAULT_RECORD_TTL_SECONDS,
        )
        return Response.status(Response.Status.CREATED).entity(response).build()
    }

    @POST
    @Path("/receipts/lookup")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sctInstPayment.read")
    @Operation(summary = "Resolve an instant-payment receipt for the authenticated caller")
    suspend fun lookupReceipt(
        request: SctInstReceiptLookupRequest?,
        @HeaderParam("X-Customer-Party-Id") customerPartyHeader: String?,
        @HeaderParam("X-Customer-Actor-Id") customerActorHeader: String?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        require(request.idempotencyKey.isNotBlank()) { "idempotencyKey is required" }
        val (partyId, actorId) = trustedCustomerProvenance(customerPartyHeader, customerActorHeader)
        val payment = submitUseCase.findReceipt(
            request.idempotencyKey,
            request.debtorAccountId,
            actorScope,
            partyId,
            actorId,
        ).awaitSuspending()
        return Response.ok(
            if (payment == null) {
                SctInstReceiptLookupResponse("UNKNOWN")
            } else {
                SctInstReceiptLookupResponse("FOUND", payment.paymentId, payment.status.name)
            },
        ).build()
    }

    // #10486 batch 7: ROLE_API admits agent-service's OWN machine principal (the sepa_instant_get tool)
    // once the shared openbank-services client loses ROLE_OPERATOR, matching the two list endpoints.
    // OPA (enforced here) grants sctInstPayment.read to service-account-openbank-agent by identity and
    // denies every other ROLE_API holder (sepa_instant_rest_ext.rego: service-agent-sct-inst-read).
    @GET
    @Path("/{paymentId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_API")
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
    fun recall(@PathParam("paymentId") paymentId: UUID, body: RecallRequest?): Uni<Response> {
        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type, so the
        // first field access threw NPE and this answered 500 (#3038). libs-runtime maps
        // IllegalArgumentException to 400.
        requireNotNull(body) { "request body is required" }
        return recallUseCase.recall(paymentId, body.reason).map { Response.ok(toResponse(it)).build() }
    }

    // PENDING on GET/list is non-authoritative: a scheme response may have been lost after
    // acceptance. Only the bound receipt lookup says whether a durable decision exists.
    private fun toResponse(p: com.openbank.sepainstant.domain.model.SctInstPayment) = SctInstPaymentResponse(
        paymentId = p.paymentId, status = p.status.name,
        debtorIban = p.debtorIban, creditorIban = p.creditorIban,
        amount = p.amount.amount, currency = p.currency, endToEndId = p.endToEndId,
        executionTimeoutAt = p.executionTimeoutAt, settledAt = p.settledAt, createdAt = p.createdAt,
    )
}
