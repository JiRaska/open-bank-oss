// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.rest

import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import com.openbank.kyb.application.port.`in`.ClaimInvitationCommand
import com.openbank.kyb.application.port.`in`.InviteCosignersCommand
import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.`in`.MatchInitiatorCommand
import com.openbank.kyb.application.port.`in`.RegistryLookupUseCase
import com.openbank.kyb.application.port.`in`.RejectCaseCommand
import com.openbank.kyb.application.port.`in`.ResolveReviewCommand
import com.openbank.kyb.application.port.`in`.SignCommand
import com.openbank.kyb.application.port.`in`.StartCaseCommand
import com.openbank.kyb.application.usecase.CaseCallerMismatchException
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.infrastructure.rest.dto.CaseResponse
import com.openbank.kyb.infrastructure.rest.dto.ClaimInvitationRequest
import com.openbank.kyb.infrastructure.rest.dto.ExtractResponse
import com.openbank.kyb.infrastructure.rest.dto.InviteCosignersRequest
import com.openbank.kyb.infrastructure.rest.dto.LookupRequest
import com.openbank.kyb.infrastructure.rest.dto.MatchInitiatorRequest
import com.openbank.kyb.infrastructure.rest.dto.RejectRequest
import com.openbank.kyb.infrastructure.rest.dto.ResolveReviewRequest
import com.openbank.kyb.infrastructure.rest.dto.SchemeResponse
import com.openbank.kyb.infrastructure.rest.dto.SignRequest
import com.openbank.kyb.infrastructure.rest.dto.StartCaseRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

/**
 * Business onboarding API (ADR-0284). Reached by the customer edge with the shared M2M token plus
 * `X-Customer-Party-Id` — the authenticated human — and by the admin console for the review
 * queue. **The customer identity comes from that header on every customer route and never from
 * the body**: a body that names a different initiator is refused before any use case runs.
 */
@Tag(name = "KYB", description = "Legal-entity verification and business onboarding (ADR-0284)")
@Path("/api/v1/kyb")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.KYC)
@Suppress("TooManyFunctions") // one thin method per state transition of the case
class KybResource {

    @Inject lateinit var lookup: RegistryLookupUseCase

    @Inject lateinit var onboarding: BusinessOnboardingUseCase

    @Inject lateinit var identity: SecurityIdentity

    @Inject lateinit var packs: com.openbank.kyb.infrastructure.registry.CountryPackRegistry

    @Inject lateinit var clock: java.time.Clock

    @GET
    @Path("/schemes")
    @Operation(
        summary = "Identifier schemes an applicant from a country may enter (national first, cross-border after)",
    )
    fun schemes(@QueryParam("country") country: String?): Response {
        val today = java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)
        val pack = packs.packFor(country, today)
        val list = when {
            pack != null -> pack.schemes
            country.isNullOrBlank() -> IdentifierScheme.entries.toList()
            else -> IdentifierScheme.forCountry(country)
        }
        return Response.ok(
            mapOf(
                "country" to country?.uppercase(),
                "pack" to
                    pack?.let {
                        mapOf(
                            "version" to it.version,
                            "registry" to it.registry.name,
                            "uboFallback" to it.uboRegister.fallback,
                        )
                    },
                "schemes" to list.map { SchemeResponse(it.name, it.country, it.displayName, it.checksum, example(it)) },
            ),
        ).build()
    }

    @POST
    @Path("/lookup")
    @Authorize(action = "kyb.lookup")
    @Operation(
        summary = "Look a business identifier up in its public register (404 unknown, 503 register down)",
    )
    suspend fun lookup(request: LookupRequest?): Response {
        requireNotNull(request) { "request body is required" }
        val extract =
            lookup.lookup(LookupCommand(request.scheme(), request.identifier(), request.declared?.toCommand()))
                ?: return Response.status(Response.Status.NOT_FOUND).entity(
                    mapOf(
                        "error" to "no register record for this identifier",
                    ),
                ).build()
        return Response.ok(ExtractResponse.from(extract)).build()
    }

    @POST
    @Path("/cases")
    @Authorize(action = "kyb.case.start", resource = "#request.initiatorPartyId")
    @Operation(summary = "Start a business onboarding case for the authenticated human as initiator")
    suspend fun start(
        request: StartCaseRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val initiator = requireNotNull(request.initiatorPartyId) { "initiatorPartyId is required" }
        requireCaller(customerPartyId, initiator)
        val lookupReq = LookupRequest(request.scheme, request.identifier, request.declared)
        val case = onboarding.start(
            StartCaseCommand(lookupReq.scheme(), lookupReq.identifier(), initiator, request.declared?.toCommand()),
        )
        return Response.created(
            URI.create("/api/v1/kyb/cases/${case.id}"),
        ).entity(CaseResponse.from(case, initiator)).build()
    }

    @GET
    @Path("/cases")
    @Authorize(action = "kyb.case.list")
    @Operation(summary = "Cases a party is involved in (customer), or by status (operator review queue)")
    suspend fun list(
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("status") status: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        if (partyId != null) {
            requireCaller(customerPartyId, partyId)
            return Response.ok(onboarding.listForParty(partyId).map { CaseResponse.from(it, partyId) }).build()
        }
        requireOperator()
        val st =
            status?.let { runCatching { CaseStatus.valueOf(it.uppercase()) }.getOrNull() } ?: CaseStatus.MANUAL_REVIEW
        return Response.ok(
            onboarding.listByStatus(st, page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE)).map {
                CaseResponse.from(it, null)
            },
        ).build()
    }

    @GET
    @Path("/cases/{id}")
    @Authorize(action = "kyb.case.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID, @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?): Response {
        val case = onboarding.get(id)
        if (customerPartyId != null) {
            val involved =
                case.initiatorPartyId == customerPartyId || case.signers.any { it.partyId == customerPartyId }
            if (!involved) return notFound()
        } else {
            requireOperator()
        }
        return Response.ok(CaseResponse.from(case, customerPartyId)).build()
    }

    @POST
    @Path("/cases/{id}/initiator")
    @Authorize(action = "kyb.case.match-initiator", resource = "#id")
    @Operation(summary = "The initiator states which listed representative they are (or that they are not listed)")
    suspend fun matchInitiator(
        @PathParam("id") id: UUID,
        request: MatchInitiatorRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val caller = requireCustomer(customerPartyId)
        val case = onboarding.matchInitiator(
            MatchInitiatorCommand(
                id,
                caller,
                request.representativeIndex,
                request.claimedName?.trim().orEmpty(),
                request.dateOfBirth,
            ),
        )
        return Response.ok(CaseResponse.from(case, caller)).build()
    }

    @POST
    @Path("/cases/{id}/cosigners")
    @Authorize(action = "kyb.case.invite", resource = "#id")
    @Operation(
        summary = "Invite listed representatives to co-sign; returns their invitation tokens to the initiator only",
    )
    suspend fun inviteCosigners(
        @PathParam("id") id: UUID,
        request: InviteCosignersRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val caller = requireCustomer(customerPartyId)
        val case = onboarding.inviteCosigners(
            InviteCosignersCommand(id, caller, request.representativeIndexes.orEmpty()),
        )
        return Response.ok(CaseResponse.from(case, caller)).build()
    }

    @POST
    @Path("/invitations/{token}/claim")
    @Authorize(action = "kyb.invitation.claim")
    @Operation(summary = "An invited representative, now identity-verified, binds their own party to the case")
    suspend fun claim(
        @PathParam("token") token: String,
        request: ClaimInvitationRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        val claimant = request?.partyId ?: customerPartyId
        val caller = requireCustomer(claimant)
        requireCaller(customerPartyId, caller)
        val case = onboarding.claimInvitation(ClaimInvitationCommand(token, caller))
        return Response.ok(CaseResponse.from(case, caller)).build()
    }

    @POST
    @Path("/cases/{id}/sign")
    @Authorize(action = "kyb.case.sign", resource = "#id")
    @Operation(summary = "Record one signer's completed signature ceremony")
    suspend fun sign(
        @PathParam("id") id: UUID,
        request: SignRequest?,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        val caller = requireCustomer(customerPartyId)
        val ref = requireNotNull(request?.signatureRef?.takeIf { it.isNotBlank() }) { "signatureRef is required" }
        val case = onboarding.sign(SignCommand(id, caller, ref))
        return Response.ok(CaseResponse.from(case, caller)).build()
    }

    @POST
    @Path("/cases/{id}/abandon")
    @Authorize(action = "kyb.case.abandon", resource = "#id")
    suspend fun abandon(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        val caller = requireCustomer(customerPartyId)
        return Response.ok(CaseResponse.from(onboarding.abandon(id, caller), caller)).build()
    }

    // --- operator review -------------------------------------------------------------------

    @POST
    @Path("/cases/{id}/review/resolve")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.KYC)
    @Authorize(action = "kyb.case.review.resolve", resource = "#id")
    @Operation(summary = "Operator confirms a manually attested extract / power of attorney and sets the signer count")
    suspend fun resolveReview(@PathParam("id") id: UUID, request: ResolveReviewRequest?): Response {
        val required = requireNotNull(request?.requiredSignatures) { "requiredSignatures is required" }
        val case = onboarding.resolveReview(ResolveReviewCommand(id, required, identity.principal?.name ?: "operator"))
        return Response.ok(CaseResponse.from(case, null)).build()
    }

    @POST
    @Path("/cases/{id}/reject")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.KYC)
    @Authorize(action = "kyb.case.reject", resource = "#id")
    suspend fun reject(@PathParam("id") id: UUID, request: RejectRequest?): Response {
        val reason =
            requireNotNull(
                request?.reason?.takeIf {
                    it.length >= MIN_REASON
                },
            ) { "reason of at least $MIN_REASON characters is required" }
        val case = onboarding.reject(RejectCaseCommand(id, reason, identity.principal?.name ?: "operator"))
        return Response.ok(CaseResponse.from(case, null)).build()
    }

    private fun requireCustomer(customerPartyId: UUID?): UUID = customerPartyId
        ?: throw CaseCallerMismatchException("$CUSTOMER_PARTY_HEADER header is required on customer routes")

    private fun requireCaller(customerPartyId: UUID?, claimed: UUID) {
        if (customerPartyId == null) {
            requireOperator()
        } else if (customerPartyId != claimed) {
            throw CaseCallerMismatchException("party $claimed is not the authenticated customer")
        }
    }

    private fun requireOperator() {
        if (!identity.roles.any { it == Roles.OPERATOR || it == Roles.ADMIN || it == Roles.KYC }) {
            throw CaseCallerMismatchException("operator role required")
        }
    }

    private fun notFound() =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "case not found")).build()

    private fun example(scheme: IdentifierScheme) = when (scheme) {
        IdentifierScheme.CZ_ICO, IdentifierScheme.SK_ICO -> "12345678"
        IdentifierScheme.PL_NIP, IdentifierScheme.PL_KRS -> "1234567890"
        IdentifierScheme.DE_HRB -> "HRB12345"
        IdentifierScheme.AT_FN -> "FN123456A"
        IdentifierScheme.GB_CRN -> "01234567"
        IdentifierScheme.FR_SIREN, IdentifierScheme.DUNS -> "123456789"
        IdentifierScheme.NL_KVK -> "12345678"
        IdentifierScheme.LEI -> "315700N6RX2TO0QO8T71"
        IdentifierScheme.EUID -> "CZOR.12345678"
        IdentifierScheme.EU_VAT -> "CZ12345678"
    }

    companion object {
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"
        private const val MAX_PAGE = 100
        private const val MIN_REASON = 10
    }
}
