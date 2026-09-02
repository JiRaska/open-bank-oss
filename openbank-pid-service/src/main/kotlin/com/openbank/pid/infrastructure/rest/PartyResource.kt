// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pid.application.port.`in`.AddRelationshipCommand
import com.openbank.pid.application.port.`in`.ChangePartyStatusCommand
import com.openbank.pid.application.port.`in`.CreatePartyCommand
import com.openbank.pid.application.port.`in`.CreatePartyUseCase
import com.openbank.pid.application.port.`in`.GetPartyUseCase
import com.openbank.pid.application.port.`in`.LinkExternalIdCommand
import com.openbank.pid.application.port.`in`.ManageRelationshipUseCase
import com.openbank.pid.application.port.`in`.PartySearchQuery
import com.openbank.pid.application.port.`in`.RegisterIdentityCommand
import com.openbank.pid.application.port.`in`.RegisterIdentityUseCase
import com.openbank.pid.application.port.`in`.ResolutionResult
import com.openbank.pid.application.port.`in`.ResolveByIndexUseCase
import com.openbank.pid.application.port.`in`.ResolveIdentityCommand
import com.openbank.pid.application.port.`in`.ResolveIdentityUseCase
import com.openbank.pid.application.port.`in`.SyncFromBankIdCommand
import com.openbank.pid.application.port.`in`.SyncFromRobCommand
import com.openbank.pid.application.port.`in`.TerminateRelationshipCommand
import com.openbank.pid.application.port.`in`.TransitionPartyCaseCommand
import com.openbank.pid.application.port.`in`.UpdateContactCommand
import com.openbank.pid.application.port.`in`.UpdateKycCommand
import com.openbank.pid.application.port.`in`.UpdatePartyUseCase
import com.openbank.pid.domain.model.Address
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.IdDocument
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.PartyRelationship
import com.openbank.pid.domain.model.PartyRole
import com.openbank.pid.domain.model.PartyStatus
import com.openbank.pid.infrastructure.rest.dto.AddRelationshipRequest
import com.openbank.pid.infrastructure.rest.dto.AddressAttributesResponse
import com.openbank.pid.infrastructure.rest.dto.AddressDto
import com.openbank.pid.infrastructure.rest.dto.CandidateSummaryResponse
import com.openbank.pid.infrastructure.rest.dto.ChangeStatusRequest
import com.openbank.pid.infrastructure.rest.dto.ContactAttributesResponse
import com.openbank.pid.infrastructure.rest.dto.CoreAttributesResponse
import com.openbank.pid.infrastructure.rest.dto.CreatePartyRequest
import com.openbank.pid.infrastructure.rest.dto.ExternalIdResponse
import com.openbank.pid.infrastructure.rest.dto.IdDocumentDto
import com.openbank.pid.infrastructure.rest.dto.KycAttributesResponse
import com.openbank.pid.infrastructure.rest.dto.LinkExternalIdRequest
import com.openbank.pid.infrastructure.rest.dto.PartyCaseLifecycleResponse
import com.openbank.pid.infrastructure.rest.dto.PartyResponse
import com.openbank.pid.infrastructure.rest.dto.RegisterIdentityRequest
import com.openbank.pid.infrastructure.rest.dto.RelationshipResponse
import com.openbank.pid.infrastructure.rest.dto.ResolvePartyRequest
import com.openbank.pid.infrastructure.rest.dto.ResolvePartyResponse
import com.openbank.pid.infrastructure.rest.dto.SyncFromBankIdRequest
import com.openbank.pid.infrastructure.rest.dto.SyncFromRobRequest
import com.openbank.pid.infrastructure.rest.dto.TerminateRelationshipRequest
import com.openbank.pid.infrastructure.rest.dto.TransitionCaseRequest
import com.openbank.pid.infrastructure.rest.dto.UpdateContactRequest
import com.openbank.pid.infrastructure.rest.dto.UpdateKycRequest
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Parties", description = "Unified identity management (PID)")
@Suppress("LongParameterList")
class PartyResource(
    private val createPartyUseCase: CreatePartyUseCase,
    private val getPartyUseCase: GetPartyUseCase,
    private val updatePartyUseCase: UpdatePartyUseCase,
    private val manageRelationshipUseCase: ManageRelationshipUseCase,
    private val resolveIdentityUseCase: ResolveIdentityUseCase,
    private val registerIdentityUseCase: RegisterIdentityUseCase,
    private val resolveByIndexUseCase: ResolveByIndexUseCase,
    private val clock: Clock,
) {

    /**
     * Register/refresh an identity in the pid index after a party is created in party-service
     * (issue #1294). Writes the RČ blind index so tier-1 dedup has data. Idempotent; M2M only.
     */
    @POST
    @Path("/register-identity")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.register")
    @Operation(summary = "Register an onboarded identity into the pid resolver index (issue #1294)")
    suspend fun registerIdentity(request: RegisterIdentityRequest): Response {
        val party = registerIdentityUseCase.register(
            RegisterIdentityCommand(
                partyId = request.partyId,
                partyType = request.partyType,
                givenName = request.givenName,
                familyName = request.familyName,
                birthdate = request.birthdate,
                birthplace = request.birthplace,
                birthNumberRaw = request.birthNumberRaw,
                keycloakSub = request.keycloakSub,
                nationalities = request.nationalities,
                eudiPidSubVerified = request.eudiPidSub,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    // ── Identity resolution (ADR-0072) ────────────────────────────────────────

    /**
     * Resolve whether an applicant already exists as a party.
     * Must be called by every party-creating flow before `POST /api/v1/parties`.
     * Only a `NO_MATCH` decision permits creating a new party.
     *
     * Access: service (M2M) + operator + admin.  The customer surface never calls this
     * directly — the edge calls it on behalf of the customer and returns only a neutral
     * pending state if the decision is `NEEDS_MANUAL_VERIFICATION`.
     *
     * The plaintext RČ in the request body is processed immediately and never stored
     * or logged; it is reduced to a keyed blind index within this request.
     */
    @POST
    @Path("/resolve")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.resolve")
    @Operation(summary = "Resolve applicant identity before party creation (ADR-0072, issue #699)")
    suspend fun resolve(request: ResolvePartyRequest): Response {
        val result = resolveIdentityUseCase.resolve(
            ResolveIdentityCommand(
                givenName = request.givenName,
                familyName = request.familyName,
                birthdate = request.birthdate,
                birthplace = request.birthplace,
                birthNumberRaw = request.birthNumberRaw,
                nationalities = request.nationalities,
            ),
        )
        return when (result) {
            is ResolutionResult.MatchExisting ->
                Response.ok(
                    ResolvePartyResponse(
                        decision = "MATCH_EXISTING",
                        partyId = result.partyId,
                        caseId = null,
                        candidates = null,
                    ),
                ).build()

            is ResolutionResult.NoMatch ->
                Response.ok(
                    ResolvePartyResponse(
                        decision = "NO_MATCH",
                        partyId = null,
                        caseId = null,
                        candidates = null,
                    ),
                ).build()

            is ResolutionResult.NeedsManualVerification ->
                Response.ok(
                    ResolvePartyResponse(
                        decision = "NEEDS_MANUAL_VERIFICATION",
                        partyId = null,
                        caseId = result.caseId,
                        candidates = result.candidates.map {
                            CandidateSummaryResponse(it.partyId, it.nameMasked, it.birthYear)
                        },
                    ),
                ).build()
        }
    }

    // ── Direct blind-index lookup (ADR-0072) ──────────────────────────────────

    /**
     * Direct party lookup by a pre-computed RČ blind index.
     *
     * The caller computes the index via `BlindIndex.compute(pepper, rodneCislo.canonical)` and
     * passes only the resulting 64-char hex string.  The endpoint returns the matched partyId or
     * 404.  No attribute cross-checks are performed here — those belong to the full
     * `POST /api/v1/parties/resolve` flow.
     *
     * Access: M2M only (openbank-service role).  This endpoint must never be exposed to
     * customer-channel traffic; only internal services that already hold the blind index may call
     * it (e.g. a downstream service correlating its own blind-index store with the pid-service).
     */
    @GET
    @Path("/pid/resolve")
    @RolesAllowed(Roles.API)
    @Authorize(action = "pid.resolve")
    @Operation(
        summary = "Resolve partyId from a pre-computed RČ blind index (ADR-0072, internal M2M)",
        description = """
            Returns the partyId if a BIRTH_NUMBER external-id row matches the supplied blind index,
            404 otherwise.  The pepper used to compute the index must be the same pepper configured
            in pid-service (OPENBANK_PID_BIRTH_NUMBER_PEPPER); cross-service pepper divergence
            produces a 404 that is operationally indistinguishable from a genuine no-match.
        """,
    )
    // `index` is nullable by necessity: JAX-RS injects null for an absent query parameter, and
    // on a suspend fun no null-check intrinsic is emitted — a non-nullable declaration made the
    // absent-parameter case NPE at `isBlank()` (a 500), i.e. exactly the case this 400 branch
    // was written for (#3624). Absent and blank collapse into the same documented envelope.
    suspend fun resolveByIndex(@QueryParam("index") index: String?): Response {
        if (index.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("code" to "INVALID_PARAMETER", "message" to "'index' query parameter is required"))
                .build()
        }
        val partyId = resolveByIndexUseCase.resolveByIndex(index)
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(mapOf("partyId" to partyId)).build()
    }

    /**
     * Link an additional external identifier (e.g. a second Keycloak sub) to an existing party —
     * the identity-unification merge of ADR-0072 §5. Called when resolution returns MATCH_EXISTING
     * for a returning person arriving through a new channel, so both identities map to one party.
     * Idempotent; 409 if the identifier already belongs to a different party.
     */
    @POST
    @Path("/{id}/external-ids")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.link")
    @Operation(summary = "Link an external identifier to an existing party (ADR-0072 §5)")
    suspend fun linkExternalId(@PathParam("id") id: UUID, request: LinkExternalIdRequest): Response {
        val party = updatePartyUseCase.linkExternalId(
            LinkExternalIdCommand(partyId = id, type = request.type, value = request.value),
        )
        return Response.ok(party.toResponse()).build()
    }

    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Create a new party (unified identity)")
    suspend fun createParty(request: CreatePartyRequest): Response {
        val party = createPartyUseCase.createParty(
            CreatePartyCommand(
                partyType = request.partyType,
                givenName = request.givenName,
                familyName = request.familyName,
                birthdate = request.birthdate,
                birthNumberEncrypted = null,
                birthNumberRaw = request.birthNumberRaw,
                nationalities = request.nationalities,
                verificationSource = request.verificationSource,
                bankIdSub = request.bankIdSub,
                initialRole = request.initialRole,
                onboardingChannel = request.onboardingChannel,
            ),
        )
        return Response.created(URI.create("/api/v1/parties/${party.id}"))
            .entity(party.toResponse())
            .build()
    }

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Get party by internal ID")
    suspend fun getById(@PathParam("id") id: UUID): Response =
        Response.ok(getPartyUseCase.getById(id).toResponse()).build()

    @GET
    @Path("/by-external-id")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Get party by external ID (bankID sub, ROB AIFO, etc.)")
    suspend fun getByExternalId(
        // Nullable by necessity — JAX-RS injects null for an absent query parameter (#3624).
        // libs-runtime maps IllegalArgumentException to 400; never add a service-local mapper (#526).
        @QueryParam("type") type: ExternalIdType?,
        @QueryParam("value") value: String?,
    ): Response {
        requireNotNull(type) { "query parameter 'type' is required" }
        requireNotNull(value) { "query parameter 'value' is required" }
        return Response.ok(getPartyUseCase.getByExternalId(type, value).toResponse()).build()
    }

    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Search parties")
    suspend fun search(
        @QueryParam("givenName") givenName: String?,
        @QueryParam("familyName") familyName: String?,
        @QueryParam("email") email: String?,
        @QueryParam("role") role: PartyRole?,
        @QueryParam("status") status: PartyStatus?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("afterId") afterId: UUID?,
    ): Response {
        val results = getPartyUseCase.search(
            PartySearchQuery(
                givenName = givenName,
                familyName = familyName,
                email = email,
                role = role,
                status = status,
                limit = limit,
                afterId = afterId,
            ),
        )
        return Response.ok(results.map { it.toResponse() }).build()
    }

    @POST
    @Path("/{id}/sync/bankid")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Sync party attributes from bankID")
    suspend fun syncFromBankId(@PathParam("id") id: UUID, request: SyncFromBankIdRequest): Response {
        val party = updatePartyUseCase.syncFromBankId(
            SyncFromBankIdCommand(
                partyId = id,
                bankIdSub = request.bankIdSub,
                givenName = request.givenName,
                familyName = request.familyName,
                birthdate = request.birthdate,
                birthNumberEncrypted = null,
                gender = request.gender,
                birthplace = request.birthplace,
                nationalities = request.nationalities,
                idDocuments = request.requireIdDocuments().map {
                    IdDocument(it.type, it.number, it.issuingCountry, it.issuedAt, it.expiresAt)
                },
                email = request.email,
                phone = request.phone,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @POST
    @Path("/{id}/sync/rob")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Sync address from ROB (Registr obyvatel)")
    suspend fun syncFromRob(@PathParam("id") id: UUID, request: SyncFromRobRequest): Response {
        val party = updatePartyUseCase.syncFromRob(
            SyncFromRobCommand(
                partyId = id,
                robAifo = request.robAifo,
                permanentAddress = request.permanentAddress?.toDomain(),
                mailingAddress = request.mailingAddress?.toDomain(),
                syncedAt = OffsetDateTime.now(clock),
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @PATCH
    @Path("/{id}/contact")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Update contact attributes")
    suspend fun updateContact(@PathParam("id") id: UUID, request: UpdateContactRequest): Response {
        val party = updatePartyUseCase.updateContact(
            UpdateContactCommand(
                partyId = id,
                email = request.email,
                phone = request.phone,
                preferredLanguage = request.preferredLanguage,
                dataBoxId = request.dataBoxId,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @PUT
    @Path("/{id}/kyc")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Update KYC/AML attributes")
    suspend fun updateKyc(@PathParam("id") id: UUID, request: UpdateKycRequest): Response {
        val party = updatePartyUseCase.updateKyc(
            UpdateKycCommand(
                partyId = id,
                kycLevel = request.kycLevel,
                amlRiskScore = request.amlRiskScore,
                pepFlag = request.pepFlag,
                sanctionsFlag = request.sanctionsFlag,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @PATCH
    @Path("/{id}/status")
    @RolesAllowed(Roles.ADMIN)
    @Authorize(action = "party.changeStatus", resource = "#id")
    @Operation(summary = "Change party status (suspend, terminate, etc.)")
    suspend fun changeStatus(@PathParam("id") id: UUID, request: ChangeStatusRequest): Response {
        val party = updatePartyUseCase.changeStatus(
            ChangePartyStatusCommand(
                partyId = id,
                newStatus = request.status,
                reason = request.reason,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @PATCH
    @Path("/{id}/case")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Transition PID verification case lifecycle")
    suspend fun transitionCase(@PathParam("id") id: UUID, request: TransitionCaseRequest): Response {
        val party = updatePartyUseCase.transitionCase(
            TransitionPartyCaseCommand(
                partyId = id,
                toStatus = request.status,
                actor = request.actor,
                reasonCode = request.reasonCode,
                reason = request.reason,
                metadata = request.metadata,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @POST
    @Path("/{id}/relationships")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Add a role/relationship to a party")
    suspend fun addRelationship(@PathParam("id") id: UUID, request: AddRelationshipRequest): Response {
        val rel = manageRelationshipUseCase.addRelationship(
            AddRelationshipCommand(
                partyId = id,
                role = request.role,
                onboardingChannel = request.onboardingChannel,
            ),
        )
        return Response.created(URI.create("/api/v1/parties/$id/relationships/${rel.id}"))
            .entity(rel.toResponse())
            .build()
    }

    @DELETE
    @Path("/{id}/relationships/{relationshipId}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Terminate a party relationship/role")
    suspend fun terminateRelationship(
        @PathParam("id") id: UUID,
        @PathParam("relationshipId") relationshipId: UUID,
        request: TerminateRelationshipRequest,
    ): Response {
        val rel = manageRelationshipUseCase.terminateRelationship(
            TerminateRelationshipCommand(
                partyId = id,
                relationshipId = relationshipId,
                reason = request.reason,
            ),
        )
        return Response.ok(rel.toResponse()).build()
    }

    private fun Party.toResponse() = PartyResponse(
        id = id, partyType = partyType, status = status,
        externalIds = externalIds.map { ExternalIdResponse(it.type, it.value, it.verifiedAt) },
        coreAttributes = CoreAttributesResponse(
            givenName = coreAttributes.givenName,
            familyName = coreAttributes.familyName,
            birthdate = coreAttributes.birthdate,
            gender = coreAttributes.gender,
            birthplace = coreAttributes.birthplace,
            nationalities = coreAttributes.nationalities,
            idDocuments = coreAttributes.idDocuments.map {
                IdDocumentDto(it.type, it.number, it.issuingCountry, it.issuedAt, it.expiresAt)
            },
            verificationSource = coreAttributes.verificationSource,
            verifiedAt = coreAttributes.verifiedAt,
        ),
        addressAttributes = addressAttributes?.let {
            AddressAttributesResponse(
                permanentAddress = it.permanentAddress?.toDto(),
                mailingAddress = it.mailingAddress?.toDto(),
                robSyncedAt = it.robSyncedAt,
            )
        },
        contactAttributes = ContactAttributesResponse(
            email = contactAttributes.email,
            emailVerifiedAt = contactAttributes.emailVerifiedAt,
            phone = contactAttributes.phone,
            phoneVerifiedAt = contactAttributes.phoneVerifiedAt,
            preferredLanguage = contactAttributes.preferredLanguage,
            dataBoxId = contactAttributes.dataBoxId,
        ),
        kycAttributes = KycAttributesResponse(
            kycLevel = kycAttributes.kycLevel,
            kycCompletedAt = kycAttributes.kycCompletedAt,
            kycExpiresAt = kycAttributes.kycExpiresAt,
            amlRiskScore = kycAttributes.amlRiskScore,
            pepFlag = kycAttributes.pepFlag,
            sanctionsFlag = kycAttributes.sanctionsFlag,
            lastAmlReviewAt = kycAttributes.lastAmlReviewAt,
        ),
        relationships = relationships.map { it.toResponse() },
        caseLifecycle = caseLifecycle?.let {
            PartyCaseLifecycleResponse(
                caseId = it.caseId.value,
                caseType = it.caseType,
                status = it.status,
                lastActor = it.lastActor,
                lastReasonCode = it.lastReasonCode,
                lastTransitionAt = it.lastTransitionAt,
                metadata = it.metadata,
            )
        },
        createdAt = createdAt, updatedAt = updatedAt, version = version,
    )

    private fun Address.toDto() = AddressDto(street, houseNumber, city, postalCode, countryCode, ruianCode)
    private fun AddressDto.toDomain() = Address(street, houseNumber, city, postalCode, countryCode, ruianCode)
    private fun PartyRelationship.toResponse() = RelationshipResponse(
        id,
        role,
        status,
        onboardedAt,
        onboardingChannel,
        terminatedAt,
        terminationReason,
    )
}
