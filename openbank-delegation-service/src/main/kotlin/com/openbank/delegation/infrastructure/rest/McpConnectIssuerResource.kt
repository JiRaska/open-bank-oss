// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.infrastructure.client.McpConnectScaReceiptClient
import com.openbank.delegation.infrastructure.persistence.repository.McpConnectRequestStore
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.security.McpConnectContext
import com.openbank.libs.security.McpConnectContextV2
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Path("/api/v1/delegations/mcp-connect/requests")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
@Suppress("TooManyFunctions") // One internal protocol owns the same authenticated request and receipt binding.
class McpConnectIssuerResource(
    private val identity: SecurityIdentity,
    private val store: McpConnectRequestStore,
    private val ownership: ResourceOwnershipClient,
    @RestClient private val sca: McpConnectScaReceiptClient,
    @ConfigProperty(name = "openbank.delegation.mcp-connect-enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "quarkus.rest-client.mcp-connect-sca-receipt.url")
    scaReceiptOrigin: URI,
) {
    init {
        if (enabled) {
            require(
                scaReceiptOrigin.scheme == "https" &&
                    scaReceiptOrigin.host != null &&
                    scaReceiptOrigin.userInfo == null,
            ) { "MCP connect requires a trusted HTTPS origin for consumed SCA receipts" }
        }
    }

    @POST
    @Authorize(action = "delegation.mcp-connect.issuer.register")
    suspend fun register(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        body: Registration?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        val request = requireNotNull(body) { "registration is required" }.toRequest()
        requireMcpConnectIdempotencyKey(idempotencyKey, request.requestRef)
        if (!store.register(request)) throw conflict()
        return envelope(request, "PENDING_SELECTION") + mapOf(
            "redirectHost" to request.redirectHost,
            "resource" to request.resource,
            "scopes" to request.scopes.sorted(),
        )
    }

    @POST
    @Path("/{requestRef}/selection")
    @Authorize(action = "delegation.mcp-connect.issuer.selection")
    suspend fun selection(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        @PathParam("requestRef") requestRef: String,
        body: McpConnectRequestStore.Binding?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        requireMcpConnectIdempotencyKey(idempotencyKey, requestRef)
        val review = find(requestRef, requireNotNull(body) { "binding is required" })
        val status = if (review.accounts.isEmpty()) "PENDING_SELECTION" else "SELECTED"
        return envelope(review.request, status) + ("accounts" to review.accounts)
    }

    @POST
    @Path("/{requestRef}/challenge")
    @Authorize(action = "delegation.mcp-connect.issuer.challenge")
    suspend fun challenge(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        @PathParam("requestRef") requestRef: String,
        body: ChallengeBinding?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        requireMcpConnectIdempotencyKey(idempotencyKey, requestRef)
        val link = requireNotNull(body) { "challenge binding is required" }
        val binding = McpConnectRequestStore.Binding(link.partyId, link.clientId, link.parJkt)
        val review = find(requestRef, binding)
        if (
            review.accounts.isEmpty() ||
            link.approvalRequestId != "mcp-connect:$requestRef" ||
            link.contextSha256 != contextOf(review).payloadSha256()
        ) {
            throw ForbiddenException("challenge does not match the committed review")
        }
        val challenge = McpConnectRequestStore.Challenge(link.challengeId, link.contextSha256)
        if (!store.bindChallenge(requestRef, binding, challenge)) throw conflict()
        return envelope(review.request, "CHALLENGE_BOUND") + mapOf(
            "challengeId" to link.challengeId,
            "approvalRequestId" to link.approvalRequestId,
            "contextSha256" to link.contextSha256,
        )
    }

    @POST
    @Path("/{requestRef}/stage")
    @Authorize(action = "delegation.mcp-connect.issuer.stage")
    suspend fun stage(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        @PathParam("requestRef") requestRef: String,
        body: StagedRequest?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        requireMcpConnectIdempotencyKey(idempotencyKey, requestRef)
        val link = requireNotNull(body) { "code binding is required" }
        val review = find(requestRef, link.binding())
        val stage = verifyConsumed(review, link)
        if (!store.stage(stage)) throw conflict()
        return stagedEnvelope(review, stage)
    }

    @POST
    @Path("/staged")
    @Authorize(action = "delegation.mcp-connect.issuer.staged")
    suspend fun staged(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        body: CodeLookup?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        val lookup = requireNotNull(body) { "code binding is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, lookup.codeBindingRef)
        require(REQUEST_REF.matches(lookup.codeBindingRef)) { "invalid code binding" }
        val binding = McpConnectRequestStore.Binding(lookup.partyId, lookup.clientId, lookup.parJkt)
        val review = store.findStaged(binding, lookup.codeBindingRef) ?: throw NotFoundException()
        val challenge = recordedChallenge(review)
        return stagedEnvelope(
            review,
            McpConnectRequestStore.StageBinding(review.request.requestRef, binding, challenge, lookup.codeBindingRef),
        )
    }

    @POST
    @Path("/{requestRef}/activate")
    @Authorize(action = "delegation.mcp-connect.issuer.activate")
    suspend fun activate(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        @PathParam("requestRef") requestRef: String,
        body: StagedRequest?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        requireMcpConnectIdempotencyKey(idempotencyKey, requestRef)
        val link = requireNotNull(body) { "refresh family is required" }
        val family = requireNotNull(link.refreshFamilyRef) { "refresh family is required" }
        require(REQUEST_REF.matches(family)) { "invalid refresh family" }
        val review = find(requestRef, link.binding())
        val stage = verifyConsumed(review, link)
        for (account in review.accounts) {
            val verdict = ownership.verifyOwnership(link.partyId, DelegationResourceType.ACCOUNT, account.accountId)
            if (verdict != OwnershipVerdict.OWNED) throw ForbiddenException("account ownership is unavailable")
        }
        val connectionId = store.activate(stage, family) ?: throw conflict()
        return stagedEnvelope(review, stage) + mapOf(
            "status" to "ACTIVE",
            "refreshFamilyRef" to family,
            "connectionId" to connectionId,
        )
    }

    @POST
    @Path("/family-status")
    @Authorize(action = "delegation.mcp-connect.issuer.family-status")
    suspend fun familyStatus(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam(DelegationResource.CUSTOMER_PARTY_HEADER) customerParty: String?,
        body: FamilyLookup?,
    ): Map<String, Any> {
        requireIssuer(customerParty)
        val lookup = requireNotNull(body) { "refresh family is required" }
        requireMcpConnectIdempotencyKey(idempotencyKey, lookup.refreshFamilyRef)
        require(REQUEST_REF.matches(lookup.refreshFamilyRef)) { "invalid refresh family" }
        val binding = McpConnectRequestStore.Binding(lookup.partyId, lookup.clientId, lookup.parJkt)
        val connectionId = store.activeFamily(binding, lookup.refreshFamilyRef) ?: throw NotFoundException()
        return mapOf(
            "status" to "ACTIVE",
            "partyId" to lookup.partyId,
            "clientId" to lookup.clientId,
            "parJkt" to lookup.parJkt,
            "refreshFamilyRef" to lookup.refreshFamilyRef,
            "connectionId" to connectionId,
        )
    }

    private fun stagedEnvelope(
        review: McpConnectRequestStore.Review,
        stage: McpConnectRequestStore.StageBinding,
    ): Map<String, Any> = envelope(review.request, "APPROVED_AWAITING_FAMILY") + mapOf(
        "redirectHost" to review.request.redirectHost,
        "resource" to review.request.resource,
        "scopes" to review.request.scopes.sorted(),
        "challengeId" to stage.challenge.challengeId,
        "contextSha256" to stage.challenge.contextSha256,
        "approvalRequestId" to "mcp-connect:${stage.requestRef}",
        "codeBindingRef" to stage.codeBindingRef,
    )

    private suspend fun verifyConsumed(
        review: McpConnectRequestStore.Review,
        link: StagedRequest,
    ): McpConnectRequestStore.StageBinding {
        require(REQUEST_REF.matches(link.codeBindingRef)) { "invalid code binding" }
        val expectedApprovalId = "mcp-connect:${review.request.requestRef}"
        val receipt = sca.read(link.challengeId)
        val matchesReview = review.challengeId == link.challengeId &&
            review.contextSha256 == link.contextSha256 &&
            link.approvalRequestId == expectedApprovalId
        val matchesChallenge = receipt.approvalRequestId == expectedApprovalId &&
            receipt.contextSha256 == link.contextSha256 &&
            receipt.challengeId == link.challengeId
        val matchesCustomer = receipt.purpose == "MCP_CLIENT_CONNECT" &&
            receipt.partyId == link.partyId &&
            receipt.decidedByPartyId == link.partyId &&
            receipt.consumedAt.isBefore(review.request.expiresAt)
        if (!matchesReview || !matchesChallenge || !matchesCustomer) {
            throw ForbiddenException("the consumed challenge does not match this connection")
        }
        return McpConnectRequestStore.StageBinding(
            review.request.requestRef,
            link.binding(),
            McpConnectRequestStore.Challenge(link.challengeId, link.contextSha256),
            link.codeBindingRef,
        )
    }

    private fun requireIssuer(customerParty: String?) {
        if (!enabled) throw NotFoundException()
        if (customerParty != null || !ServiceAccountIdentity.isClient(identity, setOf(KEYCLOAK_CONNECT_CLIENT))) {
            throw ForbiddenException("connect issuance is reserved to Keycloak")
        }
    }

    private suspend fun find(
        requestRef: String,
        binding: McpConnectRequestStore.Binding,
    ): McpConnectRequestStore.Review {
        if (!REQUEST_REF.matches(requestRef)) throw NotFoundException()
        return store.findForParty(binding.partyId, requestRef)?.takeIf {
            it.request.clientId == binding.clientId && it.request.parJkt == binding.parJkt
        } ?: throw NotFoundException()
    }

    private fun recordedChallenge(review: McpConnectRequestStore.Review): McpConnectRequestStore.Challenge {
        val id = review.challengeId
        val hash = review.contextSha256
        if (id == null || hash == null) throw conflict()
        return McpConnectRequestStore.Challenge(id, hash)
    }

    private fun envelope(request: McpConnectRequestStore.Request, status: String): Map<String, Any> = mapOf(
        "requestRef" to request.requestRef,
        "partyId" to request.partyId,
        "clientId" to request.clientId,
        "parJkt" to request.parJkt,
        "expiresAtEpochSecond" to request.expiresAt.toEpochSecond(),
        "status" to status,
    )

    private fun contextOf(review: McpConnectRequestStore.Review): McpConnectContextV2 = with(review) {
        val base = McpConnectContext(
            request.clientId,
            request.redirectHost,
            request.resource,
            request.scopes.sorted().joinToString(" "),
            request.requestRef,
            request.expiresAt.toEpochSecond(),
        )
        McpConnectContextV2.of(
            base,
            request.partyId.toString(),
            request.parJkt,
            accounts.map { McpConnectContextV2.AccountScope(it.accountId.toString(), it.capabilities) },
        )
    }

    data class Registration(
        val requestRef: String,
        val partyId: UUID,
        val clientId: String,
        val parJkt: String,
        val redirectHost: String,
        val resource: String,
        val scopes: Set<String?>,
        val expiresAtEpochSecond: Long,
    ) {
        fun toRequest(): McpConnectRequestStore.Request {
            require(REQUEST_REF.matches(requestRef) && JKT.matches(parJkt)) { "invalid connect binding" }
            require(clientId.isNotBlank() && clientId.length <= DelegationGrant.CLIENT_ID_MAX_LENGTH) {
                "invalid client"
            }
            require(redirectHost.isNotBlank() && redirectHost.length <= MAX_HOST_LENGTH) { "invalid redirect host" }
            require(resource.isNotBlank() && resource.length <= MAX_RESOURCE_LENGTH) { "invalid MCP resource" }
            val requestedScopes = scopes.mapIndexed { index, scope ->
                requireNotNull(scope) { "scopes[$index] must not be null" }
            }.toSet()
            require(
                requestedScopes.isNotEmpty() &&
                    requestedScopes.all(SCOPE::matches) &&
                    "offline_access" !in requestedScopes,
            ) {
                "invalid scopes"
            }
            val expiry = Instant.ofEpochSecond(expiresAtEpochSecond)
            require(!expiry.isAfter(Instant.now().plusSeconds(MAX_REQUEST_SECONDS))) { "invalid connect expiry" }
            return McpConnectRequestStore.Request(
                requestRef,
                partyId,
                clientId,
                parJkt,
                redirectHost,
                resource,
                requestedScopes,
                OffsetDateTime.ofInstant(expiry, ZoneOffset.UTC),
            )
        }
    }

    data class ChallengeBinding(
        val partyId: UUID,
        val clientId: String,
        val parJkt: String,
        val challengeId: UUID,
        val approvalRequestId: String,
        val contextSha256: String,
    )

    data class StagedRequest(
        val partyId: UUID,
        val clientId: String,
        val parJkt: String,
        val challengeId: UUID,
        val approvalRequestId: String,
        val contextSha256: String,
        val codeBindingRef: String,
        val refreshFamilyRef: String? = null,
    ) {
        fun binding() = McpConnectRequestStore.Binding(partyId, clientId, parJkt)
    }

    data class CodeLookup(val partyId: UUID, val clientId: String, val parJkt: String, val codeBindingRef: String)

    data class FamilyLookup(val partyId: UUID, val clientId: String, val parJkt: String, val refreshFamilyRef: String)

    private companion object {
        const val KEYCLOAK_CONNECT_CLIENT = "openbank-keycloak-mcp"
        const val MAX_HOST_LENGTH = 253
        const val MAX_RESOURCE_LENGTH = 2_048
        const val MAX_REQUEST_SECONDS = 300L
        val REQUEST_REF = Regex("^[0-9a-f]{64}$")
        val JKT = Regex("^[A-Za-z0-9_-]{43}$")
        val SCOPE = Regex("^[A-Za-z0-9._:/-]{1,100}$")
        fun conflict() = ClientErrorException("connect request is unavailable", Response.Status.CONFLICT)
    }
}

// The authority store already atomically checks natural references and the full immutable binding.
internal fun requireMcpConnectIdempotencyKey(key: String?, reference: String) {
    require(key == reference) { "Idempotency-Key must match the operation reference" }
}
