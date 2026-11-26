// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.Optional
import java.util.UUID

/** First-party app access to the bank-owned native Connect handoff and passkey proof. */
@Path("/customer/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions") // The routes share one customer-binding and authority boundary.
internal class McpNativeConnectResource(
    private val upstream: UpstreamClient,
    private val proofs: McpNativePasskeyProofService,
    private val jwt: JsonWebToken,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.edge.mcp-native-connect-enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.edge.mcp-native-connect-authority-url")
    authorityUrl: Optional<URI>,
) {
    private val authority = authorityUrl.filter {
        it.scheme == "https" &&
            !it.host.isNullOrBlank() &&
            it.userInfo == null &&
            it.query == null &&
            it.fragment == null &&
            (it.path.isNullOrEmpty() || it.path == "/")
    }.map { it.toString() }.orElse(null)

    data class ClaimRequest(val handoff: String?)
    data class ClaimResponse(val requestRef: String)

    @POST
    @Path("/mcp/connect-requests-v2/native-handoff/claim")
    @Authorize(action = "customer.mcp-native-connect.claim")
    @Blocking
    fun claim(@HeaderParam("X-Acting-For") actingFor: String?, request: ClaimRequest?): Response =
        ownApp(actingFor) { origin, owner ->
            val tokenSha256 = request?.handoff?.let(::handoffDigest)
                ?: return@ownApp error(Status.BAD_REQUEST, "Invalid Connect handoff")
            authorityPost(
                origin,
                "$AUTHORITY_PATH/claim",
                owner,
                mapOf("tokenSha256" to tokenSha256, "keycloakSubject" to owner.subject),
                tokenSha256,
            ).use { reply ->
                val result = parse<ClaimResponse>(reply)
                when {
                    result != null && REFERENCE.matches(result.requestRef) -> response(Status.OK, result)
                    reply.status == Status.OK.statusCode -> unavailable()
                    else -> authorityRefusal(reply.status)
                }
            }
        }

    @POST
    @Path("/mcp/connect-requests-v2/{requestRef}/native-passkey/begin")
    @Authorize(action = "customer.mcp-native-connect.prove")
    @Blocking
    fun begin(
        @HeaderParam("X-Acting-For") actingFor: String?,
        @PathParam("requestRef") requestRef: String?,
    ): Response = ownApp(actingFor) { origin, owner ->
        withSelected(origin, owner, requestRef) { binding ->
            val challenge = runCatching { proofs.begin(binding) }.getOrElse { return@withSelected unavailable() }
            challenge?.let { response(Status.OK, it) } ?: error(Status.CONFLICT, "Connect is no longer selectable")
        }
    }

    @POST
    @Path("/mcp/connect-requests-v2/{requestRef}/native-passkey/complete")
    @Authorize(action = "customer.mcp-native-connect.prove")
    @Blocking
    fun complete(
        @HeaderParam("X-Acting-For") actingFor: String?,
        @PathParam("requestRef") requestRef: String?,
        assertion: AuthCompleteRequestDto?,
    ): Response = ownApp(actingFor) { origin, owner ->
        if (assertion == null) return@ownApp error(Status.BAD_REQUEST, "Passkey assertion is required")
        withSelected(origin, owner, requestRef) { binding ->
            val proof = runCatching { proofs.complete(binding, assertion) }
                .getOrElse { return@withSelected unavailable() }
            if (proof == null) return@withSelected error(Status.FORBIDDEN, "Passkey proof refused")
            authorityPost(
                origin,
                "$AUTHORITY_PATH/${binding.requestRef}/mark-verified",
                owner,
                mapOf("keycloakSubject" to owner.subject, "contextSha256" to proof.binding.contextSha256),
                binding.requestRef,
            ).use { reply ->
                if (reply.status == Status.NO_CONTENT.statusCode) {
                    response(Status.NO_CONTENT)
                } else {
                    authorityRefusal(reply.status)
                }
            }
        }
    }

    private fun withSelected(
        origin: String,
        owner: NativeMcpOwner,
        requestRef: String?,
        call: (McpNativePasskeyBinding) -> Response,
    ): Response {
        if (requestRef == null || !REFERENCE.matches(requestRef)) {
            return error(Status.BAD_REQUEST, "Invalid Connect reference")
        }
        return authorityPost(
            origin,
            "$AUTHORITY_PATH/$requestRef/selected-binding",
            owner,
            mapOf("keycloakSubject" to owner.subject),
            requestRef,
        ).use { reply ->
            val raw = parse<SelectedBinding>(reply)
            val binding = raw?.let { selectedBinding(it, requestRef, owner) }
            when {
                binding != null -> call(binding)
                reply.status == Status.OK.statusCode -> unavailable()
                else -> authorityRefusal(reply.status)
            }
        }
    }

    private fun selectedBinding(
        raw: SelectedBinding,
        requestRef: String,
        owner: NativeMcpOwner,
    ): McpNativePasskeyBinding? = runCatching {
        require(raw.requestRef == requestRef)
        val par = when (raw.parBinding) {
            "dpop" -> McpNativeParBinding.Dpop(requireNotNull(raw.parJkt))
            "confidential_client" -> {
                require(raw.parJkt == null)
                McpNativeParBinding.ConfidentialClient
            }
            else -> error("Unexpected PAR proof type")
        }
        McpNativePasskeyBinding(
            requestRef,
            owner.partyId,
            owner.subject,
            raw.clientId,
            par,
            raw.contextSha256,
            raw.expiresAtEpochSecond,
        )
    }.getOrNull()

    private fun authorityPost(
        origin: String,
        path: String,
        owner: NativeMcpOwner,
        fields: Map<String, String>,
        idempotencyKey: String,
    ): Response = upstream.postToService(
        origin,
        path,
        owner.partyId.toString(),
        mapper.writeValueAsString(fields),
        idempotencyKey,
    )

    private inline fun <reified T : Any> parse(reply: Response): T? {
        if (reply.status != Status.OK.statusCode) return null
        return runCatching { mapper.readValue(reply.entity as String, T::class.java) }.getOrNull()
    }

    private fun ownApp(actingFor: String?, call: (String, NativeMcpOwner) -> Response): Response {
        if (!enabled) return error(Status.NOT_FOUND, "Connect is unavailable")
        val owner = nativeMcpAppOwner(jwt, actingFor)
            ?: return error(Status.FORBIDDEN, "Your own bank app session is required")
        val origin = authority ?: return unavailable()
        return call(origin, owner)
    }

    private fun handoffDigest(token: String): String? {
        if (!OPAQUE_TOKEN.matches(token)) return null
        val bytes = Base64.getUrlDecoder().decode(token)
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != token) return null
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun authorityRefusal(status: Int): Response = when (status) {
        Status.FORBIDDEN.statusCode -> error(Status.FORBIDDEN, "Connect proof is not permitted")
        Status.NOT_FOUND.statusCode -> error(Status.NOT_FOUND, "Connect request is unavailable")
        Status.NO_CONTENT.statusCode, Status.CONFLICT.statusCode -> error(Status.CONFLICT, "Connect is not ready")
        else -> unavailable()
    }

    private fun unavailable(): Response = error(Status.SERVICE_UNAVAILABLE, "Connect authority is unavailable")

    private fun error(status: Status, message: String): Response = response(status, mapOf("error" to message))

    private fun response(status: Status, entity: Any? = null): Response =
        Response.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").entity(entity).build()

    private data class SelectedBinding(
        val requestRef: String,
        val contextSha256: String,
        val clientId: String,
        val parBinding: String,
        val parJkt: String?,
        val expiresAtEpochSecond: Long,
    )

    private companion object {
        const val AUTHORITY_PATH = "/api/v1/delegations/mcp-connect/customer/native-handoffs"
        val REFERENCE = Regex("[0-9a-f]{64}")
        val OPAQUE_TOKEN = Regex("[A-Za-z0-9_-]{43}")
    }
}

internal data class NativeMcpOwner(val partyId: UUID, val subject: String)

internal fun nativeMcpAppOwner(jwt: JsonWebToken, actingFor: String?): NativeMcpOwner? {
    if (!actingFor.isNullOrBlank() || jwt.containsClaim("act")) return null
    val client = jwt.getClaim<Any?>("azp") as? String ?: return null
    if (client !in APP_CLIENTS || jwt.audience?.contains(EDGE_AUDIENCE) != true) return null
    val subject = jwt.subject?.takeIf { it.isNotBlank() && it.length <= MAX_SUBJECT_LENGTH } ?: return null
    val party = jwt.getClaim<String>("party_id")?.let { raw ->
        runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw }
    } ?: return null
    return NativeMcpOwner(party, subject)
}

private const val EDGE_AUDIENCE = "openbank-edge"
private val APP_CLIENTS = setOf("openbank-app", "openbank-edge-webauthn")
private const val MAX_SUBJECT_LENGTH = 255
