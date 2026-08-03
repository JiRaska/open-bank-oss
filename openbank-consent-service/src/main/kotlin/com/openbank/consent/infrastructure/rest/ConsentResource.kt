// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.consent.application.port.`in`.ActivateConsentUseCase
import com.openbank.consent.application.port.`in`.CheckConsentCommand
import com.openbank.consent.application.port.`in`.CreateConsentCommand
import com.openbank.consent.application.port.`in`.CreateConsentUseCase
import com.openbank.consent.application.port.`in`.GetConsentUseCase
import com.openbank.consent.application.port.`in`.RevokeConsentCommand
import com.openbank.consent.application.port.`in`.RevokeConsentUseCase
import com.openbank.consent.application.port.`in`.ValidateConsentCommand
import com.openbank.consent.application.port.`in`.ValidateConsentUseCase
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.infrastructure.rest.dto.ConsentResponse
import com.openbank.consent.infrastructure.rest.dto.ConsentValidationResponse
import com.openbank.consent.infrastructure.rest.dto.CreateConsentRequest
import com.openbank.consent.infrastructure.rest.dto.RevokeConsentRequest
import com.openbank.consent.infrastructure.rest.dto.ValidateConsentRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

// @Authorize alone is the fine-grained per-resource check; it must be paired with the coarse
// @RolesAllowed gate (libs-domain's own Authorize.kt docs) — every method here was previously
// missing that pairing, so an anonymous caller reached the @Authorize interceptor at all.
@Tag(name = "Consents", description = "PSD2 / GDPR consent lifecycle management (ADR-0126)")
@Path("/api/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class ConsentResource(
    private val createConsent: CreateConsentUseCase,
    private val revokeConsent: RevokeConsentUseCase,
    private val getConsent: GetConsentUseCase,
    private val validateConsent: ValidateConsentUseCase,
    private val activateConsent: ActivateConsentUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    @Operation(summary = "Create a new consent (PENDING_SCA); idempotent via tppTransactionId / X-Request-ID")
    @POST
    // Renamed from consent.create (issue #938 follow-up): "grant" is a distinctive verb so
    // adding it to rules.yaml four_eyes.verbs cannot silently four-eyes-gate every OTHER
    // money-path service's unrelated `.create` action fleet-wide.
    // Dotted-path resource extraction (ADR-0206 D1/D2): scopes the shared M2M principal's
    // consent.grant to grantee=party-service:marketing-comms only (consent-opa-bundle.yaml's
    // service-consent-m2m-marketing rule) instead of opening it fleet-wide. Human/operator
    // callers are unaffected — their existing OPA rule doesn't inspect this resource.
    @Authorize(action = "consent.grant", resource = "#request.granteeId")
    suspend fun create(
        request: CreateConsentRequest?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context uriInfo: UriInfo,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val idempotencyKey = request.tppTransactionId?.takeIf { it.isNotBlank() }
            ?: xRequestId?.takeIf { it.isNotBlank() }

        idempotencyKey?.let { key ->
            idempotencyStore.get(consentCreateKey(request.granteeId, request.partyId, key))?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val consent = createConsent.createConsent(
            CreateConsentCommand(
                partyId = request.partyId,
                granteeId = request.granteeId,
                granteeType = request.granteeType,
                granteeName = request.granteeName,
                scopes = request.scopes,
                accountIbans = request.accountIbans,
                validTo = request.validTo,
                redirectUri = request.redirectUri,
                tppTransactionId = request.tppTransactionId ?: xRequestId,
                ipAddress = null,
                userAgent = null,
            ),
        )
        val responseBody = ConsentResponse.from(consent)
        idempotencyKey?.let { key ->
            idempotencyStore.save(
                consentCreateKey(request.granteeId, request.partyId, key),
                201,
                objectMapper.writeValueAsString(responseBody),
            )
        }

        return Response.created(uriInfo.absolutePathBuilder.path(consent.id.toString()).build())
            .entity(responseBody).build()
    }

    @Operation(summary = "Get consent by ID")
    @GET
    @Path("/{id}")
    @Authorize(action = "consent.read", resource = "#id")
    suspend fun getById(@PathParam("id") id: UUID): ConsentResponse = ConsentResponse.from(getConsent.getConsent(id))

    @Operation(summary = "List all consents for a party")
    @GET
    @Path("/party/{partyId}")
    @Authorize(action = "consent.list", resource = "#partyId")
    suspend fun listByParty(@PathParam("partyId") partyId: UUID): List<ConsentResponse> =
        getConsent.listConsentsForParty(partyId).map { ConsentResponse.from(it) }

    @Operation(summary = "List all consents granted to a TPP / grantee")
    @GET
    @Path("/grantee/{granteeId}")
    @Authorize(action = "consent.list", resource = "#granteeId")
    suspend fun listByGrantee(@PathParam("granteeId") granteeId: String): List<ConsentResponse> =
        getConsent.listConsentsForGrantee(granteeId).map { ConsentResponse.from(it) }

    @Operation(summary = "Revoke an ACTIVE consent; transitions to REVOKED and enqueues ConsentRevoked event")
    @DELETE
    @Path("/{id}")
    // resource = "#granteeId", NOT "#id" (issue #2911). The M2M path (ADR-0206 D2) is gated by
    // consent-opa-bundle.yaml's service-consent-m2m-marketing rule, which requires
    // `input.resource.id == "party-service:marketing-comms"`. Binding the resource to the consent
    // UUID made that comparison unsatisfiable, so the rule's consent.revoke half was dead code and
    // every M2M revoke 403'd — while consent.grant worked, because create binds
    // `#request.granteeId`. Measured: grant 201, revoke 403, same principal.
    //
    // Human/operator callers are unaffected: they do not pass granteeId, extractResource then
    // yields no resource at all, and `operator-consent-write` does not inspect one.
    //
    // The use case still cross-checks the passed granteeId against the loaded consent's actual
    // granteeId before revoking, since the OPA decision cannot see the DB row.
    @Authorize(action = "consent.revoke", resource = "#granteeId")
    suspend fun revoke(
        @PathParam("id") id: UUID,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("granteeId") granteeId: String?,
        request: RevokeConsentRequest?,
    ): ConsentResponse {
        requireNotNull(request) { "request body is required" }
        // #3104 — same defect one argument position over: absent, partyId reached
        // RevokeConsentCommand as null and answered 500 instead of 400.
        requireNotNull(partyId) { "query parameter 'partyId' is required" }
        val consent = revokeConsent.revokeConsent(RevokeConsentCommand(id, partyId, request.reason, granteeId))
        return ConsentResponse.from(consent)
    }

    @Operation(summary = "Activate a PENDING_SCA consent after SCA challenge completes")
    @POST
    @Path("/{id}/activate")
    @Authorize(action = "consent.activate", resource = "#id")
    suspend fun activate(
        @PathParam("id") id: UUID,
        @QueryParam("scaSessionId") scaSessionId: UUID?,
    ): ConsentResponse {
        // #3104 — nullable + guard, because a guard in a non-nullable parameter's body is dead code.
        requireNotNull(scaSessionId) { "query parameter 'scaSessionId' is required" }
        return ConsentResponse.from(activateConsent.activateConsent(id, scaSessionId))
    }

    @Operation(summary = "Reject a PENDING_SCA consent (e.g. customer cancelled SCA); transitions to REJECTED")
    @POST
    @Path("/{id}/reject")
    @Authorize(action = "consent.reject", resource = "#id")
    suspend fun reject(@PathParam("id") id: UUID, @QueryParam("reason") reason: String?): ConsentResponse {
        // #3104 — a rejection with no reason is a bad request, not a broken server.
        requireNotNull(reason) { "query parameter 'reason' is required" }
        return ConsentResponse.from(activateConsent.rejectConsent(id, reason))
    }

    @Operation(summary = "Validate whether a consent covers the requested scope and account (resource servers)")
    @POST
    @Path("/{id}/validate")
    @Authorize(action = "consent.validate", resource = "#id")
    suspend fun validate(@PathParam("id") id: UUID, request: ValidateConsentRequest?): ConsentValidationResponse {
        requireNotNull(request) { "request body is required" }
        val result = validateConsent.validateConsent(
            ValidateConsentCommand(id, request.granteeId, request.requiredScope, request.accountIban),
        )
        return ConsentValidationResponse.from(result)
    }

    /**
     * Does this party hold an ACTIVE marketing (or any) consent for this grantee and scope?
     * ADR-0198 D4 requires a check per send; this is the call that makes it possible.
     *
     * Exists because `POST /{id}/validate` cannot be reached with what a sender holds. A service
     * deciding whether to send has a partyId and a channel, never a consent id, so it would have to
     * `GET /party/{partyId}` first — receiving EVERY consent that party holds, PSD2 account access
     * included, to answer a yes/no about marketing. This returns the yes/no and nothing else.
     *
     * Same `consent.validate` action as [validate] on purpose: it is the same question with a
     * different key, so it needs no new OPA action and no rego change. Authorized on the partyId,
     * which is the resource the caller actually names.
     */
    @GET
    @Path("/party/{partyId}/grantee/{granteeId}/active")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "consent.validate", resource = "#partyId")
    @Operation(
        summary = "Whether a party has an ACTIVE consent for a grantee covering a scope (ADR-0198 D4)",
    )
    suspend fun hasActiveConsent(
        @PathParam("partyId") partyId: UUID,
        @PathParam("granteeId") granteeId: String,
        @QueryParam("scope") scope: String?,
    ): ConsentCheckResponse {
        // #3104 — this handler ALREADY answered 400 when the parameter was absent, because
        // `runCatching` swallows the NPE from valueOf(null) and the requireNotNull below fires.
        // Nullable + an explicit guard so the message names the missing parameter rather than
        // reporting `unknown scope: null`, and so the declared type stops promising non-null.
        requireNotNull(scope) { "query parameter 'scope' is required" }
        val required = runCatching { ConsentScope.valueOf(scope) }.getOrNull()
        requireNotNull(required) { "unknown scope: $scope" }
        val granted = validateConsent.hasActiveConsent(
            CheckConsentCommand(partyId = partyId, granteeId = granteeId, requiredScope = required),
        )
        return ConsentCheckResponse(granted = granted)
    }

    private fun consentCreateKey(granteeId: String, partyId: UUID, requestId: String) =
        "consent:create:$granteeId:$partyId:$requestId"
}

/**
 * The whole answer: a boolean. No consent id, no scopes, no validity window — a caller that
 * received those could cache them, and ADR-0198 requires a check per send rather than a copy.
 */
data class ConsentCheckResponse(val granted: Boolean)
