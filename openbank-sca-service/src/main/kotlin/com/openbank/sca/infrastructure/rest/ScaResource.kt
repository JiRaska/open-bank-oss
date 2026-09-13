// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.sca.application.port.`in`.ConsumeScaCommand
import com.openbank.sca.application.port.`in`.ConsumeScaUseCase
import com.openbank.sca.application.port.`in`.EnrollDeviceCommand
import com.openbank.sca.application.port.`in`.EnrollDeviceUseCase
import com.openbank.sca.application.port.`in`.GetScaUseCase
import com.openbank.sca.application.port.`in`.InitiateScaCommand
import com.openbank.sca.application.port.`in`.InitiateScaUseCase
import com.openbank.sca.application.port.`in`.ListDevicesQuery
import com.openbank.sca.application.port.`in`.ListDevicesUseCase
import com.openbank.sca.application.port.`in`.RecordDeviceDecisionCommand
import com.openbank.sca.application.port.`in`.RecordDeviceDecisionUseCase
import com.openbank.sca.application.port.`in`.VerifyScaCommand
import com.openbank.sca.application.port.`in`.VerifyScaUseCase
import com.openbank.sca.application.usecase.CredentialAlreadyEnrolledException
import com.openbank.sca.application.usecase.DeviceNotEnrolledException
import com.openbank.sca.application.usecase.DeviceOwnershipMismatchException
import com.openbank.sca.application.usecase.InvalidDeviceAssertionException
import com.openbank.sca.application.usecase.ScaChallengeAlreadyConsumedException
import com.openbank.sca.application.usecase.ScaChallengeExpiredException
import com.openbank.sca.application.usecase.ScaChallengeMaxAttemptsException
import com.openbank.sca.application.usecase.ScaChallengeNotApprovedException
import com.openbank.sca.application.usecase.ScaChallengeNotAwaitingException
import com.openbank.sca.application.usecase.ScaChallengeNotFoundException
import com.openbank.sca.application.usecase.ScaChallengePartyMismatchException
import com.openbank.sca.application.usecase.ScaDynamicLinkingMismatchException
import com.openbank.sca.application.usecase.ScaMethodNotDeliverableException
import com.openbank.sca.application.usecase.ScaVerificationFailedException
import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import com.openbank.sca.domain.model.SignatureAlgorithm
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant
import java.util.UUID

data class InitiateScaRequest(
    val partyId: UUID,
    val purpose: ScaPurpose,
    val preferredMethod: ScaMethod?,
    val dynamicLinkingData: DynamicLinkingData?,
    val redirectUrl: String?,
)

data class VerifyScaRequest(val partyId: UUID, val otp: String?)

data class EnrollDeviceRequest(
    val credentialId: String,
    /** Base64 X.509 SubjectPublicKeyInfo of the device public key. */
    val publicKey: String,
    val algorithm: SignatureAlgorithm,
)

data class EnrolledDeviceResponse(
    val id: UUID,
    val partyId: UUID,
    val credentialId: String,
    val algorithm: SignatureAlgorithm,
    val enrolledAt: String,
) {
    companion object {
        fun from(d: EnrolledDevice) =
            EnrolledDeviceResponse(d.id, d.partyId, d.credentialId, d.algorithm, d.createdAt.toString())
    }
}

data class RecordDecisionRequest(
    val credentialId: String,
    val decision: DeviceDecisionType,
    /** Base64 signature over the challenge's dynamic-linking payload. */
    val signature: String,
)

data class ScaChallengeResponse(
    val id: UUID,
    val partyId: UUID,
    val purpose: ScaPurpose,
    val method: ScaMethod,
    val status: ScaStatus,
    val expiresAt: String,
    val completedAt: String?,
    val consumedAt: String?,
    val attemptCount: Int,
    val maxAttempts: Int,
) {
    companion object {
        fun from(c: ScaChallenge) = ScaChallengeResponse(
            id = c.id,
            partyId = c.partyId,
            purpose = c.purpose,
            method = c.method,
            status = c.status,
            expiresAt = c.expiresAt.toString(),
            completedAt = c.completedAt?.toString(),
            consumedAt = c.consumedAt?.toString(),
            attemptCount = c.attemptCount,
            maxAttempts = c.maxAttempts,
        )
    }
}

/**
 * A pending SCA challenge projected for the app's approval list (#8): the dynamic-linking data
 * (amount, creditor) is surfaced so the customer sees WHAT they are approving before signing.
 */
data class PendingScaResponse(
    val id: UUID,
    val purpose: ScaPurpose,
    val method: ScaMethod,
    val amount: String?,
    val currency: String?,
    val creditorIban: String?,
    val creditorName: String?,
    val reference: String?,
    val expiresAt: String,
    val createdAt: String,
) {
    companion object {
        fun from(c: ScaChallenge) = PendingScaResponse(
            id = c.id,
            purpose = c.purpose,
            method = c.method,
            amount = c.dynamicLinkingData?.amount,
            currency = c.dynamicLinkingData?.currency,
            creditorIban = c.dynamicLinkingData?.creditorIban,
            creditorName = c.dynamicLinkingData?.creditorName,
            reference = c.dynamicLinkingData?.reference,
            expiresAt = c.expiresAt.toString(),
            createdAt = c.createdAt.toString(),
        )
    }
}

/**
 * Compare-and-consume body (settlement gate): the caller states the operation it is about
 * to execute; the challenge is spent only when the device-signed dynamic-linking data
 * authorises exactly that operation. `creditor` is the creditor IBAN (SEPA) or the Czech
 * "number/bankcode" account (domestic) — whatever the device displayed and signed.
 */
data class ConsumeScaRequest(
    val partyId: UUID,
    val amount: String? = null,
    val currency: String? = null,
    val creditor: String? = null,
    /** Document content address (SHA-256), for a DOCUMENT_SIGNING challenge (ADR-0169 D2). */
    val documentSha256: String? = null,
    /** The signature ceremony this consume is scoped to, for a DOCUMENT_SIGNING challenge. */
    val ceremonyId: String? = null,
    /** The card this consume is scoped to, for a CARD_MANAGEMENT challenge. */
    val cardId: String? = null,
    /** The card operation being executed (`LIMIT_INCREASE`, `REVEAL_DETAILS`, ...), for a CARD_MANAGEMENT challenge. */
    val cardAction: String? = null,
)

@Path("/api/v1/sca")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Suppress("LongParameterList") // one use-case port per operation (hexagonal), wired by Arc
class ScaResource(
    private val initiateSca: InitiateScaUseCase,
    private val verifySca: VerifyScaUseCase,
    private val getSca: GetScaUseCase,
    private val enrollDevice: EnrollDeviceUseCase,
    private val listDevices: ListDevicesUseCase,
    private val recordDecision: RecordDeviceDecisionUseCase,
    private val consumeSca: ConsumeScaUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    // SecurityIdentity carries the authenticated principal across coroutine dispatch
    // (smallrye-context-propagation). Used for ownership enforcement on device enrollment.
    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/challenges")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "scaChallenge.initiate")
    suspend fun initiate(
        request: InitiateScaRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
    ): Response {
        val requestKey = idempotencyKey?.takeIf { it.isNotBlank() } ?: xRequestId?.takeIf { it.isNotBlank() }
        requestKey?.let { key ->
            idempotencyStore.get(scaCreateKey(request.partyId, key))?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val challenge = initiateSca.initiate(
            InitiateScaCommand(
                partyId = request.partyId,
                purpose = request.purpose,
                preferredMethod = request.preferredMethod,
                dynamicLinkingData = request.dynamicLinkingData,
                redirectUrl = request.redirectUrl,
            ),
        )
        val responseBody = ScaChallengeResponse.from(challenge)
        requestKey?.let { key ->
            idempotencyStore.save(
                scaCreateKey(request.partyId, key),
                201,
                objectMapper.writeValueAsString(responseBody),
                300,
            )
        }
        return Response.status(201).entity(responseBody).build()
    }

    @POST
    @Path("/challenges/{id}/verify")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "scaChallenge.verify", resource = "#id")
    suspend fun verify(@PathParam("id") id: UUID, request: VerifyScaRequest): ScaChallengeResponse {
        val challenge = verifySca.verify(VerifyScaCommand(id, request.partyId, request.otp))
        return ScaChallengeResponse.from(challenge)
    }

    @GET
    @Path("/challenges/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "scaChallenge.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): ScaChallengeResponse =
        ScaChallengeResponse.from(getSca.getChallenge(id))

    /**
     * Live challenges awaiting a decision for a party — the "pending approvals" list behind the
     * decoupled/push SCA flow (#8). Unlike [get] this projects the dynamic-linking data (amount,
     * creditor) so the app can render WHAT is being approved. ROLE_CUSTOMER is allowed because the
     * edge forwards the caller's own partyId (same stance as [listDevices]).
     */
    @GET
    @Path("/parties/{partyId}/challenges/pending")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_CUSTOMER")
    @Authorize(action = "scaChallenge.read", resource = "#partyId")
    suspend fun listPending(@PathParam("partyId") partyId: UUID): List<PendingScaResponse> =
        getSca.listPendingByParty(partyId).map { PendingScaResponse.from(it) }

    /**
     * List device credentials enrolled to a party (ADR-0021, ADR-0068 onboarding cockpit).
     * Includes ROLE_CUSTOMER (unlike the other endpoints here) because this one carries its
     * own ownership check below — a customer-realm caller can only ever list their own devices.
     */
    @GET
    @Path("/parties/{partyId}/devices")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_CUSTOMER")
    @Authorize(action = "device.list", resource = "#partyId")
    suspend fun listDevices(@PathParam("partyId") partyId: UUID): List<EnrolledDeviceResponse> {
        val principalName = identity.principal?.name
        if (principalName != null && !identity.hasRole("ROLE_OPERATOR") && !identity.hasRole("ROLE_ADMIN")) {
            runCatching { UUID.fromString(principalName) }.getOrNull()?.let { principalPartyId ->
                if (principalPartyId != partyId) throw ForbiddenException("Cannot list devices for another party")
            }
        }
        return listDevices.listDevices(ListDevicesQuery(partyId)).map { EnrolledDeviceResponse.from(it) }
    }

    /**
     * Enrol a device credential to a party (ADR-0021). Includes ROLE_CUSTOMER for the same
     * reason as [listDevices] — the ownership check below is the real gate for a customer
     * caller.
     */
    @POST
    @Path("/parties/{partyId}/devices")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_CUSTOMER")
    @Authorize(action = "device.enroll", resource = "#partyId")
    suspend fun enroll(@PathParam("partyId") partyId: UUID, request: EnrollDeviceRequest): Response {
        // P1 ownership enforcement (defense-in-depth over OPA advisory mode, ADR-0021 security review):
        // the authenticated principal may only enroll devices for their OWN partyId.
        // When the customer realm (ADR-0065) issues JWTs, the 'sub' claim carries the partyId;
        // in the operator realm 'sub' is the operator user id — operators with ROLE_OPERATOR
        // may enroll on behalf of a party (service-desk credential reset path, future scope).
        // For now: reject if sub == UUID && sub != partyId (i.e. the caller is a party, not an operator).
        val principalName = identity.principal?.name
        if (principalName != null && !identity.hasRole("ROLE_OPERATOR") && !identity.hasRole("ROLE_ADMIN")) {
            runCatching { UUID.fromString(principalName) }.getOrNull()?.let { principalPartyId ->
                if (principalPartyId != partyId) throw ForbiddenException("Cannot enroll device for another party")
            }
        }
        val device = enrollDevice.enroll(
            EnrollDeviceCommand(
                partyId = partyId,
                credentialId = request.credentialId,
                publicKeySpkiB64 = request.publicKey,
                algorithm = request.algorithm,
            ),
        )
        return Response.status(201).entity(EnrolledDeviceResponse.from(device)).build()
    }

    /**
     * Record an out-of-band approval/denial from the enrolled device. Authenticated as the
     * device/party (a different principal from the verify caller). Includes ROLE_CUSTOMER: the
     * real security boundary here is the signature check inside [recordDecision] (verified
     * against the challenge's dynamic-linking data), not caller identity — an attacker can't
     * forge a decision without the enrolled device's private key even with a valid ROLE_CUSTOMER
     * token for a different party.
     */
    @POST
    @Path("/challenges/{id}/decision")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_CUSTOMER")
    @Authorize(action = "scaChallenge.decide", resource = "#id")
    suspend fun decide(@PathParam("id") id: UUID, request: RecordDecisionRequest): ScaChallengeResponse {
        val challenge = recordDecision.recordDecision(
            RecordDeviceDecisionCommand(
                challengeId = id,
                credentialId = request.credentialId,
                decision = request.decision,
                signatureB64 = request.signature,
            ),
        )
        return ScaChallengeResponse.from(challenge)
    }

    /**
     * Spend an approved challenge on the operation it authorised (ADR-0021 settlement gate;
     * single-use per RTS Art. 5). Called by the customer edge — an M2M, operator-realm
     * principal — immediately BEFORE forwarding a payment to the money path, so a payment can
     * only ever execute behind a device-signed, amount+payee-bound, unconsumed approval.
     *
     * The same endpoint gates document signing (`documentSha256`+`ceremonyId`) and card
     * management (`cardId`+`cardAction`): the caller states the operation it is about to execute
     * in whichever shape applies, and EVERY linking field is compared, so a challenge raised for
     * one shape can never be spent on another (a payment challenge consumed with a `cardId`, or a
     * card challenge consumed with an `amount`, both 409).
     */
    @POST
    @Path("/challenges/{id}/consume")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "scaChallenge.consume", resource = "#id")
    suspend fun consume(@PathParam("id") id: UUID, request: ConsumeScaRequest): ScaChallengeResponse {
        val challenge = consumeSca.consume(
            ConsumeScaCommand(
                challengeId = id,
                expectedPartyId = request.partyId,
                amount = request.amount,
                currency = request.currency,
                creditor = request.creditor,
                documentSha256 = request.documentSha256,
                ceremonyId = request.ceremonyId,
                cardId = request.cardId,
                cardAction = request.cardAction,
            ),
        )
        return ScaChallengeResponse.from(challenge)
    }

    private fun scaCreateKey(partyId: UUID, requestKey: String) = "sca:initiate:$partyId:$requestKey"
}

private fun err(code: ErrorCode, msg: String) = ApiError(
    traceId = Ids.randomId().toString(),
    status = code.httpStatus,
    code = code.code,
    message = msg,
    timestamp = Instant.now(),
)

@Provider
class ScaNotFoundMapper : ExceptionMapper<ScaChallengeNotFoundException> {
    override fun toResponse(e: ScaChallengeNotFoundException): Response =
        Response.status(404).entity(err(ErrorCode.NOT_FOUND, e.message ?: "Not found")).build()
}

/**
 * 422, not 400: the request is well formed and the method is a valid enum value — this deployment
 * simply cannot deliver it. Same status as an expired challenge, which is also a "valid request,
 * cannot proceed" answer.
 */
private const val UNPROCESSABLE_ENTITY = 422

@Provider
class ScaMethodNotDeliverableMapper : ExceptionMapper<ScaMethodNotDeliverableException> {
    override fun toResponse(e: ScaMethodNotDeliverableException): Response = Response.status(UNPROCESSABLE_ENTITY)
        .entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Method not deliverable"))
        .build()
}

@Provider
class ScaExpiredMapper : ExceptionMapper<ScaChallengeExpiredException> {
    override fun toResponse(e: ScaChallengeExpiredException): Response =
        Response.status(422).entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Expired")).build()
}

@Provider
class ScaMaxAttemptsMapper : ExceptionMapper<ScaChallengeMaxAttemptsException> {
    override fun toResponse(e: ScaChallengeMaxAttemptsException): Response =
        Response.status(429).entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Too many attempts")).build()
}

@Provider
class ScaVerificationFailedMapper : ExceptionMapper<ScaVerificationFailedException> {
    override fun toResponse(e: ScaVerificationFailedException): Response =
        Response.status(401).entity(err(ErrorCode.UNAUTHORIZED, e.message ?: "Verification failed")).build()
}

@Provider
class ScaNotAwaitingMapper : ExceptionMapper<ScaChallengeNotAwaitingException> {
    override fun toResponse(e: ScaChallengeNotAwaitingException): Response =
        Response.status(409).entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Not awaiting decision")).build()
}

@Provider
class DeviceNotEnrolledMapper : ExceptionMapper<DeviceNotEnrolledException> {
    override fun toResponse(e: DeviceNotEnrolledException): Response =
        Response.status(404).entity(err(ErrorCode.NOT_FOUND, e.message ?: "Device not enrolled")).build()
}

// Handles both the pre-check conflict and the TOCTOU unique-constraint race (23505 → 409 Conflict).
@Provider
class DeviceCredentialConflictMapper : ExceptionMapper<CredentialAlreadyEnrolledException> {
    override fun toResponse(e: CredentialAlreadyEnrolledException): Response = Response.status(Response.Status.CONFLICT)
        .entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Credential conflict")).build()
}

@Provider
class DeviceOwnershipMismatchMapper : ExceptionMapper<DeviceOwnershipMismatchException> {
    override fun toResponse(e: DeviceOwnershipMismatchException): Response =
        Response.status(403).entity(err(ErrorCode.FORBIDDEN, e.message ?: "Device ownership mismatch")).build()
}

@Provider
class InvalidDeviceAssertionMapper : ExceptionMapper<InvalidDeviceAssertionException> {
    override fun toResponse(e: InvalidDeviceAssertionException): Response =
        Response.status(401).entity(err(ErrorCode.UNAUTHORIZED, e.message ?: "Invalid device assertion")).build()
}

@Provider
class ScaNotApprovedMapper : ExceptionMapper<ScaChallengeNotApprovedException> {
    override fun toResponse(e: ScaChallengeNotApprovedException): Response =
        Response.status(ErrorCode.VALIDATION_ERROR.httpStatus)
            .entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Challenge not approved")).build()
}

@Provider
class ScaAlreadyConsumedMapper : ExceptionMapper<ScaChallengeAlreadyConsumedException> {
    override fun toResponse(e: ScaChallengeAlreadyConsumedException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Challenge already consumed")).build()
}

@Provider
class ScaPartyMismatchMapper : ExceptionMapper<ScaChallengePartyMismatchException> {
    override fun toResponse(e: ScaChallengePartyMismatchException): Response =
        Response.status(Response.Status.FORBIDDEN)
            .entity(err(ErrorCode.FORBIDDEN, e.message ?: "Challenge party mismatch")).build()
}

@Provider
class ScaDynamicLinkingMismatchMapper : ExceptionMapper<ScaDynamicLinkingMismatchException> {
    override fun toResponse(e: ScaDynamicLinkingMismatchException): Response = Response.status(Response.Status.CONFLICT)
        .entity(err(ErrorCode.VALIDATION_ERROR, e.message ?: "Dynamic linking mismatch")).build()
}
