// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.usecase.CreatePaymentProposalCommand
import com.openbank.delegation.application.usecase.PaymentProposalService
import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalHashMismatchException
import com.openbank.delegation.domain.model.PaymentProposalLimitException
import com.openbank.delegation.domain.model.PaymentProposalNotFoundException
import com.openbank.delegation.domain.model.PaymentProposalNotPermittedException
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalPayload
import com.openbank.delegation.domain.model.PaymentProposalStateException
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0383 D6 — the proposal store's API. Two callers, two disjoint halves, enforced HERE by the
 * caller's verified client identity (azp bound to its own service-account, #12448) and again by
 * OPA (`delegation.proposal.*` in delegation_rest_ext.rego):
 *
 *  - **mcp-service** may only `POST /` — create a proposal. It can read nothing back beyond the
 *    create response and can never approve, reject or record an execution.
 *  - **customer-edge** may list, read, approve, reject and record execution — always for the
 *    customer it authenticated, whose party it stamps as `X-Customer-Party-Id`, and never create.
 *
 * Approving here records the outcome of the customer's own SCA, which the edge consumed first. It
 * does not, and cannot, initiate a payment.
 */
@Path("/api/v1/payment-proposals")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
@Suppress("TooManyFunctions") // five routes, each a thin public handler over a private body
class PaymentProposalResource(
    private val proposals: PaymentProposalService,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * Every POST here requires an `Idempotency-Key` (money-path rule, #8351). The state machine is
     * already replay-safe (dedupe on create, revision CAS on every transition); the key adds an
     * exact replay of the first answer, and a key reused for a DIFFERENT request is refused (409).
     * The key is scoped by operation and proposal, so it can never replay another proposal's answer.
     *
     * Only a DEFINITIVE answer is stored. A 5xx is released, never saved: the edge sends a FIXED key
     * per proposal for reject (`mcp-proposal-reject-<id>`), so a stored transient failure would be
     * replayed to every retry for the record's whole TTL and the customer could never reject it.
     */
    internal suspend fun idempotent(
        operation: String,
        scope: String,
        key: String?,
        request: Any,
        block: suspend () -> Response,
    ): Response {
        require(!key.isNullOrBlank() && key.length <= IDEMPOTENCY_KEY_MAX) { "Idempotency-Key header is required" }
        val storeKey = "payment-proposal:$operation:$scope:$key"
        val hash = sha256(objectMapper.writeValueAsString(request))
        when (val reserved = idempotencyStore.reserve(storeKey, hash)) {
            is ReserveResult.Replay -> return Response.status(reserved.record.statusCode)
                .entity(reserved.record.responseBody).type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true").build()
            ReserveResult.Mismatch -> return proposalError(
                Response.Status.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was used for a different request",
            )
            ReserveResult.InFlight -> return proposalError(
                Response.Status.CONFLICT,
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "a request with this Idempotency-Key is in progress",
            )
            ReserveResult.Reserved -> Unit
        }
        val response = try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException, // release on ANY failure, then rethrow
        ) {
            idempotencyStore.release(storeKey, hash)
            throw e
        }
        if (response.status >= SERVER_ERROR_MIN) {
            idempotencyStore.release(storeKey, hash)
            return response
        }
        idempotencyStore.save(storeKey, hash, response.status, objectMapper.writeValueAsString(response.entity))
        return response
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @POST
    @Authorize(action = "delegation.proposal.create", resource = "#request.partyId")
    suspend fun create(
        request: CreatePaymentProposalRequest?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        requireCaller(MCP_PRINCIPAL)
        val body = requireNotNull(request) { "request body is required" }
        val party = requireNotNull(body.partyId) { "partyId is required" }
        return idempotent("create", party.toString(), idempotencyKey, body) { createNow(body) }
    }

    private suspend fun createNow(body: CreatePaymentProposalRequest): Response {
        val created = proposals.create(
            CreatePaymentProposalCommand(
                partyId = requireNotNull(body.partyId) { "partyId is required" },
                mcpClientId = requireText(body.mcpClientId, "mcpClientId", CLIENT_ID_MAX),
                keyThumbprint = requireText(body.keyThumbprint, "keyThumbprint", THUMBPRINT_MAX),
                payload = PaymentProposalPayload(
                    operationType = requireNotNull(body.operationType) { "operationType is required" },
                    debtorAccountId = requireNotNull(body.debtorAccountId) { "debtorAccountId is required" },
                    creditorIban = requireText(body.creditorIban, "creditorIban", IBAN_MAX)
                        .replace(" ", "").uppercase(),
                    creditorName = PaymentProposalPayload.normalize(
                        requireText(body.creditorName, "creditorName", PaymentProposalPayload.MAX_NAME),
                    ),
                    amount = requireNotNull(body.amount) { "amount is required" },
                    currency = requireText(body.currency, "currency", CURRENCY_LEN).uppercase(),
                    reference = body.reference?.takeIf { it.isNotBlank() }?.let(PaymentProposalPayload::normalize),
                ),
            ),
        )
        val status = if (created.deduplicated) Response.Status.OK else Response.Status.CREATED
        return Response.status(status).entity(McpProposalReceipt.of(created.proposal, created.deduplicated)).build()
    }

    @GET
    @Authorize(action = "delegation.proposal.read", resource = "#partyId")
    suspend fun listPending(
        @QueryParam("partyId") partyId: UUID?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): List<PaymentProposalView> {
        val party = requireCustomer(partyId, customerPartyId)
        val now = OffsetDateTime.now()
        return proposals.listPending(party).map { PaymentProposalView.of(it, now) }
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "delegation.proposal.read", resource = "#id")
    suspend fun get(
        @PathParam("id") id: UUID,
        @QueryParam("partyId") partyId: UUID?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): PaymentProposalView {
        val party = requireCustomer(partyId, customerPartyId)
        return PaymentProposalView.of(proposals.get(id, party), OffsetDateTime.now())
    }

    @POST
    @Path("/{id}/approve")
    @Authorize(action = "delegation.proposal.approve", resource = "#id")
    suspend fun approve(
        @PathParam("id") id: UUID,
        request: ProposalDecisionRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val body = requireNotNull(request) { "request body is required" }
        val party = requireCustomer(body.partyId, customerPartyId)
        return idempotent("approve", "$id:$party", idempotencyKey, body) {
            Response.ok(approveNow(id, party, body)).build()
        }
    }

    private suspend fun approveNow(id: UUID, party: UUID, body: ProposalDecisionRequest): PaymentProposalView {
        val hash = requireText(body.payloadSha256, "payloadSha256", SHA256_LEN)
        return PaymentProposalView.of(proposals.approve(id, party, hash), OffsetDateTime.now())
    }

    @POST
    @Path("/{id}/reject")
    @Authorize(action = "delegation.proposal.reject", resource = "#id")
    suspend fun reject(
        @PathParam("id") id: UUID,
        request: ProposalDecisionRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val body = requireNotNull(request) { "request body is required" }
        val party = requireCustomer(body.partyId, customerPartyId)
        return idempotent("reject", "$id:$party", idempotencyKey, body) { Response.ok(rejectNow(id, party)).build() }
    }

    private suspend fun rejectNow(id: UUID, party: UUID): PaymentProposalView =
        PaymentProposalView.of(proposals.reject(id, party), OffsetDateTime.now())

    @POST
    @Path("/{id}/execution")
    @Authorize(action = "delegation.proposal.execution", resource = "#id")
    suspend fun recordExecution(
        @PathParam("id") id: UUID,
        request: ProposalExecutionRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val body = requireNotNull(request) { "request body is required" }
        val party = requireCustomer(body.partyId, customerPartyId)
        return idempotent("execution", "$id:$party", idempotencyKey, body) {
            Response.ok(recordExecutionNow(id, party, body)).build()
        }
    }

    private suspend fun recordExecutionNow(id: UUID, party: UUID, body: ProposalExecutionRequest): PaymentProposalView {
        val hash = requireText(body.payloadSha256, "payloadSha256", SHA256_LEN)
        return PaymentProposalView.of(
            proposals.recordExecution(id, party, hash, body.paymentId),
            OffsetDateTime.now(),
        )
    }

    private fun requireCaller(principal: String) {
        if (!ServiceAccountIdentity.isPrincipal(identity, principal)) {
            throw ForbiddenException("this payment-proposal operation is not available to the caller")
        }
    }

    /** The edge, acting for the customer it authenticated — and for no one else. */
    private fun requireCustomer(claimed: UUID?, customerPartyId: UUID?): UUID {
        requireCaller(EDGE_PRINCIPAL)
        if (claimed == null || customerPartyId == null || claimed != customerPartyId) {
            throw ForbiddenException("payment proposals are visible only to their own customer")
        }
        return claimed
    }

    private fun requireText(value: String?, field: String, max: Int): String {
        require(!value.isNullOrBlank()) { "$field is required" }
        require(value.length <= max) { "$field must be at most $max characters" }
        return value.trim()
    }

    companion object {
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val IDEMPOTENCY_KEY_MAX = 255
        private const val SERVER_ERROR_MIN = 500
        const val MCP_PRINCIPAL = "service-account-openbank-mcp"
        const val EDGE_PRINCIPAL = "service-account-openbank-edge"
        private const val CLIENT_ID_MAX = 255
        private const val THUMBPRINT_MAX = 43
        private const val IBAN_MAX = 42
        private const val CURRENCY_LEN = 3
        private const val SHA256_LEN = 64
    }
}

data class CreatePaymentProposalRequest(
    val partyId: UUID? = null,
    val mcpClientId: String? = null,
    val keyThumbprint: String? = null,
    val operationType: PaymentProposalOperationType? = null,
    val debtorAccountId: UUID? = null,
    val creditorIban: String? = null,
    val creditorName: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val reference: String? = null,
)

data class ProposalDecisionRequest(val partyId: UUID? = null, val payloadSha256: String? = null)

data class ProposalExecutionRequest(
    val partyId: UUID? = null,
    val payloadSha256: String? = null,
    /** The created payment's id; null records that execution failed after approval. */
    val paymentId: UUID? = null,
)

/**
 * What mcp-service gets back: an id, the state and a bank-rendered summary — never an approval
 * handle, a challenge or anything the client could present to make the payment happen.
 */
data class McpProposalReceipt(
    val proposalId: UUID,
    val status: String,
    val deduplicated: Boolean,
    val expiresAt: OffsetDateTime,
    val summary: String,
) {
    companion object {
        fun of(p: PaymentProposal, deduplicated: Boolean) = McpProposalReceipt(
            proposalId = p.id,
            status = p.status.name,
            deduplicated = deduplicated,
            expiresAt = p.expiresAt,
            summary = "Proposed ${p.payload.canonicalAmount} ${p.payload.currency} to ${p.payload.creditorName} " +
                "(${maskIban(p.payload.creditorIban)}). Nothing has been paid: the customer must approve it " +
                "in the bank app with strong customer authentication before it expires.",
        )

        private const val IBAN_VISIBLE = 4

        private fun maskIban(iban: String): String = iban.take(IBAN_VISIBLE) + "…" + iban.takeLast(IBAN_VISIBLE)
    }
}

/** The customer's view: the exact canonical instruction and the hash the device will sign. */
data class PaymentProposalView(
    val id: UUID,
    val partyId: UUID,
    val status: String,
    val mcpClientId: String,
    val operationType: PaymentProposalOperationType,
    val debtorAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val amount: String,
    val currency: String,
    val reference: String?,
    val payloadSha256: String,
    val approvalRequestId: String,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val decidedAt: OffsetDateTime?,
    val executedPaymentId: UUID?,
) {
    companion object {
        fun of(p: PaymentProposal, now: OffsetDateTime) = PaymentProposalView(
            id = p.id,
            partyId = p.partyId,
            status = p.effectiveStatus(now).name,
            mcpClientId = p.mcpClientId,
            operationType = p.payload.operationType,
            debtorAccountId = p.payload.debtorAccountId,
            creditorIban = p.payload.creditorIban,
            creditorName = p.payload.creditorName,
            amount = p.payload.canonicalAmount,
            currency = p.payload.currency,
            reference = p.payload.reference,
            payloadSha256 = p.payloadSha256,
            approvalRequestId = PaymentProposal.approvalRequestId(p.id),
            createdAt = p.createdAt,
            expiresAt = p.expiresAt,
            decidedAt = p.decidedAt,
            executedPaymentId = p.executedPaymentId,
        )
    }
}

private fun proposalError(status: Response.Status, code: String, message: String?): Response = Response.status(status)
    .type(MediaType.APPLICATION_JSON)
    .entity(mapOf("status" to status.statusCode, "code" to code, "error" to message))
    .build()

@Provider
class PaymentProposalNotFoundMapper : ExceptionMapper<PaymentProposalNotFoundException> {
    override fun toResponse(exception: PaymentProposalNotFoundException): Response =
        proposalError(Response.Status.NOT_FOUND, "PROPOSAL_NOT_FOUND", exception.message)
}

@Provider
class PaymentProposalStateMapper : ExceptionMapper<PaymentProposalStateException> {
    override fun toResponse(exception: PaymentProposalStateException): Response =
        proposalError(Response.Status.CONFLICT, "PROPOSAL_NOT_PENDING", exception.message)
}

@Provider
class PaymentProposalHashMismatchMapper : ExceptionMapper<PaymentProposalHashMismatchException> {
    override fun toResponse(exception: PaymentProposalHashMismatchException): Response =
        proposalError(Response.Status.CONFLICT, "PROPOSAL_HASH_MISMATCH", exception.message)
}

@Provider
class PaymentProposalLimitMapper : ExceptionMapper<PaymentProposalLimitException> {
    override fun toResponse(exception: PaymentProposalLimitException): Response =
        proposalError(Response.Status.TOO_MANY_REQUESTS, "PROPOSAL_LIMIT_REACHED", exception.message)
}

@Provider
class PaymentProposalNotPermittedMapper : ExceptionMapper<PaymentProposalNotPermittedException> {
    override fun toResponse(exception: PaymentProposalNotPermittedException): Response =
        proposalError(Response.Status.FORBIDDEN, "PROPOSAL_NOT_PERMITTED", exception.message)
}
