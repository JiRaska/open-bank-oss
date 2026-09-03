// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.authz.Authorize
import com.openbank.libs.flags.FeatureClient
import com.openbank.libs.flags.FeatureFlag
import com.openbank.party.application.port.`in`.AddDocumentCommand
import com.openbank.party.application.port.`in`.CreatePartyCommand
import com.openbank.party.application.port.`in`.ErasePartyCommand
import com.openbank.party.application.port.`in`.MergePartyCommand
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.port.`in`.PayeeLimitExceededException
import com.openbank.party.application.port.`in`.ResolvePartyByRcCommand
import com.openbank.party.application.port.`in`.SavePayeeCommand
import com.openbank.party.application.port.`in`.SearchPartiesQuery
import com.openbank.party.application.port.`in`.SelfRegisterPartyCommand
import com.openbank.party.application.port.`in`.UpdateMarketingConsentCommand
import com.openbank.party.application.port.`in`.UpdatePartyCommand
import com.openbank.party.application.port.`in`.UploadDocumentCommand
import com.openbank.party.domain.model.Address
import com.openbank.party.domain.model.DocumentType
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyClassification
import com.openbank.party.domain.model.PartyGdprExport
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import com.openbank.party.domain.model.Payee
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.MultipartForm
import org.jboss.resteasy.reactive.PartType
import java.net.URI
import java.time.Instant
import java.util.UUID

@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Parties")
class PartyResource {

    @Inject lateinit var partyUseCase: PartyUseCase

    @Inject lateinit var flags: FeatureClient

    // Instance<> rather than a hard @Inject: quarkus-oidc is the only producer of JsonWebToken in
    // this service, and %dev/%test disable OIDC (no Keycloak available there) — a direct @Inject
    // fails CDI validation at boot with no bean satisfying the type. Resolving lazily degrades to
    // an anonymous caller (no subject/claims) when OIDC is off, which the @RolesAllowed/@Authenticated
    // checks already turn into a 401/403 before any of these accessors run in a real unauthenticated
    // request.
    @Inject
    lateinit var jwtInstance: Instance<JsonWebToken>

    private val jwt: JsonWebToken?
        get() = if (jwtInstance.isResolvable) jwtInstance.get() else null

    @Inject lateinit var auditPublisher: AuditEventPublisher

    @Inject @io.quarkus.arc.Unremovable
    lateinit var securityIdentity: SecurityIdentity

    /**
     * The customer channel's M2M identity (ADR-0065). customer-edge validates the data subject's
     * own JWT (openbank-customers realm) and deliberately does NOT forward it: every upstream hop
     * carries the edge's own client_credentials token from the operator realm, plus
     * `X-Customer-Party-Id` resolved from the customer's token (`UpstreamClient` KDoc). So the
     * `isSelf` branch below — written for a subject-JWT caller — can never be true for a call the
     * subject actually made, and the edge principal holds neither ROLE_ADMIN nor ROLE_DPO
     * (`realm-template.json`: `service-account-openbank-edge` carries ROLE_OPERATOR alone). Every
     * proxied Art. 15 / Art. 20 request was therefore a 403, and the two rights were unreachable
     * by any data subject (#8421).
     *
     * Kotlin initializer AND `defaultValue`, matching `UpstreamClient`: field injection runs after
     * construction and overwrites the initializer, so config still wins, while a hand-built
     * instance in a unit test is not left with an uninitialized `lateinit`.
     */
    @ConfigProperty(
        name = "openbank.party.gdpr.customer-edge-principal",
        defaultValue = DEFAULT_CUSTOMER_EDGE_PRINCIPAL,
    )
    var gdprCustomerEdgePrincipal: String = DEFAULT_CUSTOMER_EDGE_PRINCIPAL

    /**
     * Whether this CALLER may exercise a subject's Art. 15 / Art. 20 right on the subject's behalf.
     * Deliberately takes no request data: the decision rests only on the authenticated principal
     * and on configuration (CodeQL `java/tainted-permissions-check`; same split as lending-service's
     * `CustomerCreditJourneyResource.callerIsPermitted`). A blank value refuses every call rather
     * than admitting any ROLE_OPERATOR holder — real staff carry that role too, so the identity
     * match is the load-bearing half, exactly as `rest.rego`'s `edge-service-audit-customer`
     * documents for the sibling privacy-centre route.
     */
    private fun callerIsCustomerEdge(): Boolean {
        val permitted = gdprCustomerEdgePrincipal
        return permitted.isNotBlank() && securityIdentity.principal?.name == permitted
    }

    /**
     * The subject the (already-authorised) edge is asking for. A query SCOPE, not a permission.
     * Nullable on purpose: JAX-RS injects `null` for an absent header, and a non-nullable
     * declaration would make the absent case a 500 before the body ever runs.
     */
    private fun headerSubject(partyHeader: String?): UUID? =
        partyHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * The fourth accepted caller shape: the customer edge, asking for the subject it has already
     * authenticated. The header must name the SAME party as the path, so the edge cannot read a
     * subject other than the one whose token it validated — the same ownership shape
     * `uploadDocument` above already applies to a self-registering caller.
     */
    private fun edgeActsForSubject(partyHeader: String?, id: UUID): Boolean =
        callerIsCustomerEdge() && headerSubject(partyHeader) == id

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Operation(
        summary = "List parties (paginated). Optional ?status= filter for onboarding cockpit funnel views (ADR-0068).",
    )
    suspend fun listParties(
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
        @QueryParam("status") statusParam: String?,
    ): Response {
        val status = statusParam?.uppercase()?.let { runCatching { PartyStatus.valueOf(it) }.getOrNull() }
        // ADR-0067 pilot: first live feature-flag evaluation in the fleet. Cosmetic, fail-static —
        // surfaces the resolved variant in a response header so the flip is observable via curl,
        // without changing the response body or any business logic. Flag-as-code: party-list-enriched.
        val listMode = if (flags.enabled("party-list-enriched")) "enriched" else "standard"
        return Response.ok(partyUseCase.listParties(page, size.coerceIn(1, 100), status))
            .header("X-Party-List-Mode", listMode)
            .build()
    }

    @GET
    @Path("/search")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @FeatureFlag(flag = "party-search")
    @Operation(
        summary = "Search parties by name (trigram), cursor-paginated (ADR-0055). Gated by feature flag party-search.",
    )
    suspend fun searchParties(
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        val page = partyUseCase.searchParties(SearchPartiesQuery(q, limit, cursor))
        return Response.ok(
            mapOf("data" to page.data.map { it.toSimpleResponse() }, "pagination" to page.pagination),
        ).build()
    }

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")
    @Operation(summary = "Create a new party (customer or company)")
    suspend fun createParty(
        req: CreatePartyRequest,
        // Nullable by necessity — JAX-RS injects null for an absent header (#526, #3624).
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        requireNotNull(idempotencyKey) { "header 'Idempotency-Key' is required" }
        val classification = req.classification()
        require(classification != PartyClassification.SYNTHETIC || securityIdentity.hasRole("ROLE_ADMIN")) {
            "only ROLE_ADMIN may create a synthetic party"
        }
        val party = partyUseCase.createParty(req.toCommand(idempotencyKey, classification))
        return Response.created(URI.create("/api/v1/parties/${party.id}")).entity(party.toResponse()).build()
    }

    /**
     * Pay-to-phone directory lookup (ROLE_API — the customer edge, never a browser).
     *
     * POST, not GET: the request body is a list of phone-number hashes derived from the caller's
     * address book, and those have no business sitting in a URL, an access log or a proxy cache.
     * Only parties who opted into being discoverable can come back, and a hash that matches
     * nothing is not recorded anywhere.
     */
    @POST
    @Path("/directory/lookup")
    @RolesAllowed("ROLE_API")
    @Operation(summary = "Match phone-number hashes against parties who opted into being discoverable")
    suspend fun lookupDirectory(req: DirectoryLookupRequest): Response =
        Response.ok(mapOf("matches" to partyUseCase.lookupByPhoneHashes(req.requireHashes()))).build()

    /** Turn this party's pay-to-phone findability on or off. Revocable at any time. */
    @PUT
    @Path("/{id}/discoverable")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Opt a party in or out of pay-to-phone discoverability")
    suspend fun setDiscoverable(@PathParam("id") id: UUID, req: DiscoverableRequest): Response =
        if (partyUseCase.updateDiscoverable(id, req.discoverable)) {
            Response.ok(mapOf("discoverable" to req.discoverable)).build()
        } else {
            Response.status(Response.Status.NOT_FOUND).build()
        }

    @GET
    @Path("/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Operation(summary = "Get party by ID")
    suspend fun getParty(@PathParam("id") id: UUID): Response =
        Response.ok(partyUseCase.getParty(id).toResponse()).build()

    @PATCH
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.update", resource = "#id")
    @Operation(summary = "Update party contact details or material master data")
    suspend fun updateParty(@PathParam("id") id: UUID, req: UpdatePartyRequest): Response {
        val party = partyUseCase.updateParty(
            UpdatePartyCommand(
                id = id,
                email = req.email,
                phone = req.phone,
                address = req.address?.toDomain(),
                tradingName = req.tradingName,
                legalName = req.legalName,
                dateOfBirth = req.dateOfBirth,
                nationality = req.nationality,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    /**
     * Post-onboarding marketing-consent toggle (mobile app Profile screen). Deliberately its own
     * endpoint rather than folded into [updateParty]: `consentGdpr` is NOT exposed here — it's an
     * immutable onboarding-time record, not a live togglable consent (see [UpdateMarketingConsentCommand]
     * kdoc). Audited (ADR-0086): a consent-state change is a compliance-relevant event same as the
     * GDPR export/erase operations below, even though it isn't itself a GDPR-article action.
     */
    @PATCH
    @Path("/{id}/consent")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.consent.update", resource = "#id")
    @Operation(summary = "Update the party's post-onboarding marketing consent")
    suspend fun updateConsent(@PathParam("id") id: UUID, req: UpdateConsentRequest): Response {
        // Old value for the Art 30 audit trail below — read before the write so it reflects the
        // state actually being changed FROM, not a stale/racing re-read after.
        val before = partyUseCase.getParty(id).consentMarketing
        val party = partyUseCase.updateMarketingConsent(UpdateMarketingConsentCommand(id, req.marketingConsent))
        auditPublisher.publish(
            AuditEvent(
                // The caller here is ALWAYS the customer-edge's M2M service identity (ROLE_OPERATOR
                // client-credentials token), never the customer directly — same trust boundary as
                // every other edge->party-service call (registerParty, updateParty, …). actorType
                // reflects that; the customer whose consent changed is resourceId (= the party id),
                // not the actor.
                actorId = jwt?.subject ?: jwt?.name ?: "unknown",
                actorType = "SERVICE",
                operation = "party.consent.marketing-updated",
                resourceType = "party",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
                payload = mapOf(
                    "marketingConsentBefore" to (before?.toString() ?: "null"),
                    "marketingConsentAfter" to req.marketingConsent.toString(),
                ),
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @POST
    @Path("/{id}/documents")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")
    @Operation(summary = "Add identity document to party")
    suspend fun addDocument(@PathParam("id") id: UUID, req: AddDocumentRequest): Response {
        val doc = partyUseCase.addDocument(
            AddDocumentCommand(
                id,
                DocumentType.valueOf(req.documentType),
                req.documentNumber,
                req.issuingCountry,
                req.expiryDate,
            ),
        )
        return Response.status(201).entity(doc).build()
    }

    @GET
    @Path("/{id}/documents")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Operation(summary = "List party documents")
    suspend fun listDocuments(@PathParam("id") id: UUID): Response = Response.ok(partyUseCase.listDocuments(id)).build()

    @PUT
    @Path("/{id}/kyc-status")
    @RolesAllowed("ROLE_ADMIN", "ROLE_KYC")
    @Operation(summary = "Update KYC status (called by kyc-service)")
    suspend fun updateKycStatus(@PathParam("id") id: UUID, req: KycStatusRequest): Response {
        val party = partyUseCase.updateKycStatus(id, KycStatus.valueOf(req.kycStatus))
        return Response.ok(party.toResponse()).build()
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ROLE_ADMIN")
    @Operation(summary = "Erase party — GDPR Art. 17 Right to Erasure (anonymizes all PII)")
    suspend fun eraseParty(@PathParam("id") id: UUID): Response {
        partyUseCase.eraseParty(ErasePartyCommand(id))
        // ADR-0118 / ADR-0086: GDPR Art. 17 erasure is a state-changing PII operation —
        // record who erased which subject for the Art. 30 records-of-processing trail.
        auditGdpr(operation = "party.erase", partyId = id, gdprArticle = "17")
        return Response.noContent().build()
    }

    // @Authenticated (not @RolesAllowed) because this endpoint accepts three different valid
    // caller shapes — ROLE_ADMIN, ROLE_DPO, or the subject's own JWT — which the method body
    // below already checks; @RolesAllowed alone can't express "self OR one of these roles".
    // Previously carried no annotation at all — reachable with no identity whatsoever, unlike
    // every other endpoint on this resource.
    @GET
    @Path("/{id}/gdpr-export")
    @Authenticated
    @Operation(summary = "Export all PII held for a party — GDPR Art. 15 Right of Access (ADR-0118)")
    suspend fun exportPartyGdpr(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) partyHeader: String?,
    ): Response {
        val isAdmin = securityIdentity.hasRole("ROLE_ADMIN")
        val isDpo = securityIdentity.hasRole("ROLE_DPO")
        val isSelf = jwt?.subject != null && jwt?.subject == partyUseCase.getPartyKeycloakSub(id)
        val byStaffOrSubject = isAdmin || isDpo || isSelf
        val viaEdge = !byStaffOrSubject && edgeActsForSubject(partyHeader, id)
        if (!byStaffOrSubject && !viaEdge) return Response.status(Response.Status.FORBIDDEN).build()
        val export = partyUseCase.exportPartyData(id)
        // ADR-0118 / ADR-0086: a subject-access read exposes the full PII set — audit the
        // access itself (Art. 30). Emitted only after a successful fetch; a 404 (party not
        // found) throws before this line, so no SUCCESS event is recorded for a miss.
        auditGdpr(
            operation = "party.gdpr-export",
            partyId = id,
            gdprArticle = "15",
            channel = channelOf(isSelf, viaEdge),
        )
        return Response.ok(export.toResponse()).build()
    }

    // Same @Authenticated three-shape access as exportPartyGdpr (ADR-0204 D5): ROLE_ADMIN,
    // ROLE_DPO, or the subject's own JWT. The audit event carries gdprArticle="20" — distinct
    // from "15" so the two rights stay separable in the Art. 30 record of processing.
    @GET
    @Path("/{id}/gdpr-portability-export")
    @Authenticated
    @Operation(
        summary = "Export the subject's portable data — GDPR Art. 20 Right to Data Portability (ADR-0204)",
        description = "Consent/contract-basis data only (no Art. 6(1)(c) legal-obligation fields). " +
            "Includes transaction history with counterparty IBANs redacted to their bank-code " +
            "prefix (Art. 20(4)). Art. 20(2) direct transmission is not offered (ADR-0204 D4).",
    )
    suspend fun exportPartyPortability(
        @PathParam("id") id: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) partyHeader: String?,
    ): Response {
        val isAdmin = securityIdentity.hasRole("ROLE_ADMIN")
        val isDpo = securityIdentity.hasRole("ROLE_DPO")
        val isSelf = jwt?.subject != null && jwt?.subject == partyUseCase.getPartyKeycloakSub(id)
        val byStaffOrSubject = isAdmin || isDpo || isSelf
        val viaEdge = !byStaffOrSubject && edgeActsForSubject(partyHeader, id)
        if (!byStaffOrSubject && !viaEdge) return Response.status(Response.Status.FORBIDDEN).build()
        val export = partyUseCase.exportPartyPortabilityData(id)
        auditGdpr(
            operation = "party.gdpr-portability-export",
            partyId = id,
            gdprArticle = "20",
            channel = channelOf(isSelf, viaEdge),
        )
        return Response.ok(export.toResponse()).build()
    }

    /**
     * Emit an [AuditEvent] for a GDPR operation onto the libs audit pipeline (ADR-0086 chain).
     * Actor is the authenticated operator (JWT subject); no raw PII is placed in the payload —
     * only the regulatory article reference, per the Art. 30 records-of-processing requirement.
     */
    @POST
    @Path("/{id}/merge")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    // `resource = "#id"` is load-bearing for the four-eyes gate, not decoration: the interceptor
    // stamps the PendingApproval with query.resource?.id, so WITHOUT it every approval would be
    // created (and matched) with resourceId=null — an approval a checker granted to merge party A
    // would then satisfy a merge of party B by the same maker. See ApprovalResource.
    @Authorize(action = "party.merge", resource = "#id")
    @Operation(
        summary = "Merge a duplicate party into a surviving party (ADR-0179)",
        description = "Retires {id} as a duplicate, preserving all PII and history. NOT erasure: " +
            "no anonymization, no PARTY_ERASED event. Refuses while the duplicate still owns an " +
            "open account — sweep the balances via POST /api/v1/transactions/merge-sweep " +
            "(transaction-service, ADR-0179) and close " +
            "the account first. Four-eyes gated (ADR-0155): when four-eyes enforcement is on, the " +
            "first call returns 202 with an approvalId; a DIFFERENT operator decides it via " +
            "PATCH /api/v1/parties/approvals/{approvalId} and the maker retries this call with an " +
            "X-Approval-Id header.",
    )
    suspend fun mergeParty(@PathParam("id") id: UUID, req: MergePartyRequest): Response {
        val merged = partyUseCase.mergeParty(
            MergePartyCommand(
                id = id,
                mergedIntoPartyId = req.mergedIntoPartyId,
                reason = req.reason,
                approvalReference = req.approvalReference,
            ),
        )
        // The retirement of an identity is a state-changing PII operation: record who did it,
        // which survivor was chosen, and the approval that authorised the balance sweep.
        auditPublisher.publish(
            AuditEvent(
                actorId = jwt?.subject ?: jwt?.name ?: "unknown",
                actorType = "HUMAN",
                operation = "party.merge",
                resourceType = "party",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
                payload = mapOf(
                    "merged_into_party_id" to req.mergedIntoPartyId.toString(),
                    "reason" to req.reason,
                    "approval_reference" to (req.approvalReference ?: ""),
                ),
            ),
        )
        return Response.ok(merged.toResponse()).build()
    }

    private suspend fun auditGdpr(
        operation: String,
        partyId: UUID,
        gdprArticle: String,
        channel: String = CHANNEL_STAFF,
    ) {
        auditPublisher.publish(
            AuditEvent(
                actorId = jwt?.subject ?: jwt?.name ?: "unknown",
                actorType = "HUMAN",
                operation = operation,
                resourceType = "party",
                resourceId = partyId.toString(),
                result = AuditResult.SUCCESS,
                // `channel` is what keeps the Art. 30 record honest once the edge can call these:
                // actorId is the edge's service account for every subject-initiated export, so
                // without it a subject exercising their own right and a staff member reading their
                // file are indistinguishable in the trail.
                payload = mapOf("gdpr_article" to gdprArticle, "channel" to channel),
            ),
        )
    }

    private fun channelOf(isSelf: Boolean, viaEdge: Boolean): String =
        if (isSelf || viaEdge) CHANNEL_SUBJECT else CHANNEL_STAFF

    // ─── Mobile self-registration endpoints ───────────────────────────────────────

    @GET
    @Path("/me")
    @Authenticated
    @Operation(summary = "Get my party (mobile: returns party for the calling Keycloak sub)")
    suspend fun getMyParty(): Response {
        val sub = jwt?.subject ?: return Response.status(401).build()
        val party = partyUseCase.getMyParty(sub)
            ?: return Response.status(404).entity(mapOf("code" to "NOT_REGISTERED")).build()
        return Response.ok(party.toResponse()).build()
    }

    @POST
    @Path("/self-register")
    @Authenticated
    @Operation(summary = "Self-register as a new party (mobile onboarding). Idempotent by Keycloak sub.")
    suspend fun selfRegister(req: SelfRegisterRequest): Response {
        val sub = jwt?.subject ?: return Response.status(401).build()
        val emailVerified = jwt?.getClaim<Boolean>("email_verified") ?: false
        if (!emailVerified) {
            return Response.status(403)
                .entity(mapOf("code" to "EMAIL_NOT_VERIFIED", "message" to "Verify your email before registering"))
                .build()
        }
        val (party, isNew) = partyUseCase.selfRegisterParty(
            SelfRegisterPartyCommand(
                keycloakSub = sub,
                emailVerified = emailVerified,
                partyType = PartyType.valueOf(req.partyType),
                legalName = req.legalName,
                email = jwt?.getClaim("email") ?: req.email,
                phone = req.phone,
                dateOfBirth = req.dateOfBirth,
                nationality = req.nationality,
                address = req.address?.toDomain(),
            ),
        )
        return if (isNew) {
            Response.created(URI.create("/api/v1/parties/${party.id}")).entity(party.toResponse()).build()
        } else {
            Response.ok(party.toResponse()).header("X-Resumed", "true").build()
        }
    }

    @POST
    @Path("/{id}/documents/upload")
    @Authenticated
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload KYC document image (mobile). Stores binary content server-side.")
    suspend fun uploadDocument(@PathParam("id") id: UUID, @MultipartForm form: DocumentUploadForm): Response {
        val sub = jwt?.subject ?: return Response.status(401).build()
        // Verify caller owns this party
        val party = partyUseCase.getMyParty(sub)
        if (party == null || party.id != id) return Response.status(403).build()
        // Guard against oversized uploads (Sprint 1: bytea storage; 10 MB hard limit)
        val maxBytes = 10 * 1024 * 1024 // 10 MB
        if (form.content.size > maxBytes) {
            return Response.status(413)
                .entity(mapOf("code" to "FILE_TOO_LARGE", "maxBytes" to maxBytes))
                .build()
        }
        val file = partyUseCase.uploadDocument(
            UploadDocumentCommand(
                partyId = id,
                documentType = DocumentType.valueOf(form.documentType.uppercase()),
                fileName = form.fileName,
                mimeType = form.mimeType ?: "application/octet-stream",
                content = form.content,
            ),
        )
        return Response.status(201).entity(
            mapOf(
                "id" to file.id,
                "partyId" to file.partyId,
                "documentType" to file.documentType,
                "fileName" to file.fileName,
                "mimeType" to file.mimeType,
                "uploadedAt" to file.uploadedAt,
            ),
        ).build()
    }

    // ─── Saved payees (TOP-10 #5) ──────────────────────────────────────────────
    // Server side of the mobile app's device-local PayeeStore. Same trust boundary as
    // /{id}/consent above: the caller is ALWAYS the customer-edge's M2M service identity, which
    // has already resolved [id] from the customer's own JWT before calling — never a
    // client-supplied id the customer chose. No separate ownership check needed here for the
    // same reason updateConsent doesn't have one: the edge is the trust boundary, not this path.

    @GET
    @Path("/{id}/payees")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_API")
    @Authorize(action = "party.payees.read", resource = "#id")
    @Operation(summary = "List a party's saved payees, newest first (mobile)")
    suspend fun listPayees(@PathParam("id") id: UUID): Response =
        Response.ok(partyUseCase.listPayees(id).map { it.toResponse() }).build()

    @PUT
    @Path("/{id}/payees")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.payees.write", resource = "#id")
    @Operation(summary = "Save (or update) a payee — upsert by IBAN (mobile)")
    suspend fun savePayee(@PathParam("id") id: UUID, req: SavePayeeRequest): Response {
        if (req.name.isBlank() || req.iban.isBlank()) {
            return Response.status(HTTP_BAD_REQUEST).entity(mapOf("code" to "MISSING_FIELD")).build()
        }
        val payee = try {
            partyUseCase.savePayee(SavePayeeCommand(partyId = id, name = req.name, iban = req.iban, bic = req.bic))
        } catch (_: PayeeLimitExceededException) {
            return Response.status(HTTP_UNPROCESSABLE_ENTITY).entity(mapOf("code" to "PAYEE_LIMIT_EXCEEDED")).build()
        }
        return Response.ok(payee.toResponse()).build()
    }

    @DELETE
    @Path("/{id}/payees/{iban}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "party.payees.write", resource = "#id")
    @Operation(summary = "Remove a saved payee by IBAN (mobile). Idempotent.")
    suspend fun deletePayee(@PathParam("id") id: UUID, @PathParam("iban") iban: String): Response {
        partyUseCase.deletePayee(id, iban)
        return Response.noContent().build()
    }

    @GET
    @Path("/{id}/documents/{fileId}/content")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(summary = "Download KYC document content (operators only)")
    suspend fun getDocumentContent(@PathParam("id") id: UUID, @PathParam("fileId") fileId: UUID): Response {
        val file = partyUseCase.getDocumentContent(partyId = id, fileId = fileId)
            ?: return Response.status(404)
                .entity(mapOf("code" to "FILE_NOT_FOUND"))
                .type(MediaType.APPLICATION_JSON)
                .build()
        return Response.ok(file.content)
            .type(file.mimeType)
            .header("Content-Disposition", "attachment; filename=\"${file.fileName ?: fileId}\"")
            .header("X-Document-Type", file.documentType.name)
            .build()
    }

    /**
     * ADR-0072: blind-index dedup gate. Resolves a party by Czech RČ without exposing the RČ
     * to the response. Returns 200 + party summary when found, 404 when no match, 503 when
     * pepper is unconfigured (dedup is off and callers must not assume uniqueness).
     *
     * Internal-only endpoint — requires ROLE_API so only trusted back-end callers
     * (customer-edge, onboarding service) can use it during the self-registration flow.
     */
    @POST
    @Path("/resolve")
    @RolesAllowed("ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "party:resolve")
    @Operation(summary = "Resolve a party by RČ blind index (ADR-0072 dedup gate, internal only)")
    suspend fun resolveParty(req: ResolvePartyRequest): Response {
        val party = partyUseCase.resolvePartyByRc(ResolvePartyByRcCommand(req.rc))
            ?: return if (!partyUseCase.isDedupAvailable()) {
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(
                        ApiError(
                            UUID.randomUUID().toString(),
                            Response.Status.SERVICE_UNAVAILABLE.statusCode,
                            "DEDUP_UNAVAILABLE",
                            "RČ dedup pepper not configured; uniqueness not enforced",
                            timestamp = Instant.now(),
                        ),
                    ).build()
            } else {
                Response.status(Response.Status.NOT_FOUND)
                    .entity(
                        ApiError(
                            UUID.randomUUID().toString(),
                            Response.Status.NOT_FOUND.statusCode,
                            "PARTY_NOT_FOUND",
                            "No party matches the supplied RČ",
                            timestamp = Instant.now(),
                        ),
                    ).build()
            }
        return Response.ok(party.toSimpleResponse()).build()
    }

    companion object {
        /**
         * Set by customer-edge's `UpstreamClient.PARTY_HEADER` on every proxied customer call, from
         * the customer's own validated JWT — never from a client-supplied value.
         */
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"

        /**
         * `service-account-<clientId>` is deterministic for a Keycloak client_credentials token, and
         * `rest.rego` already hardcodes this exact string in three rules
         * (`edge-service-notification`, `edge-service-consent`, `edge-service-audit-customer`).
         * Defaulted rather than left blank on purpose: a GDPR right that is unreachable unless an
         * operator remembers an env var reproduces the very defect #8421 reports.
         */
        const val DEFAULT_CUSTOMER_EDGE_PRINCIPAL = "service-account-openbank-edge"

        /** Art. 30 record: the subject exercised their own right (self-JWT, or via the edge). */
        const val CHANNEL_SUBJECT = "subject"

        /** Art. 30 record: a DPO or admin read the subject's file on the subject's behalf. */
        const val CHANNEL_STAFF = "staff"
    }
}

/** ADR-0072: RČ supplied by an internal caller; never echoed back in the response. */
data class ResolvePartyRequest(val rc: String)

data class SelfRegisterRequest(
    val partyType: String = "INDIVIDUAL",
    val legalName: String,
    val email: String, // fallback pokud není v JWT
    val phone: String?,
    val dateOfBirth: String?,
    val nationality: String?,
    val address: AddressRequest?,
)

class DocumentUploadForm {
    @FormParam("type")
    @PartType(MediaType.TEXT_PLAIN)
    lateinit var documentType: String

    @FormParam("file")
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    lateinit var content: ByteArray

    @FormParam("filename")
    @PartType(MediaType.TEXT_PLAIN)
    var fileName: String? = null

    @FormParam("mimeType")
    @PartType(MediaType.TEXT_PLAIN)
    var mimeType: String? = null
}

data class CreatePartyRequest(
    val partyType: String,
    val legalName: String,
    val tradingName: String?,
    val dateOfBirth: String?,
    val nationality: String?,
    val taxId: String?,
    // email is required by the contract (openapi.yaml: required[...email]) and downstream
    // (Party/PartyEntity NOT NULL + unique). It is declared nullable here ONLY so a request
    // that omits it deserialises cleanly and fails our own explicit check below — a non-null
    // Kotlin field would make Jackson hard-fail first and return a silent, body-less 400.
    val registrationNumber: String?,
    val email: String?,
    val phone: String?,
    val address: AddressRequest?,
    // Caller-supplied party id (ADR-0069 §B1): the customer edge passes the Keycloak `sub`
    // here so party id == sub and the principal binding holds without a KC admin client.
    // The endpoint is operator-realm-only, so customers cannot mint arbitrary ids.
    val id: String? = null,
    // Onboarding consent capture (mobile app "Agreement" step, ADR-0069). Null = not
    // asked/answered — operator-created parties never send these.
    val consentGdpr: Boolean? = null,
    val consentMarketing: Boolean? = null,
    /** Explicit only for bank-owned production canaries; omitted stays CUSTOMER. */
    val classification: String? = null,
) {
    fun classification(): PartyClassification = classification?.let {
        PartyClassification.valueOf(it.uppercase())
    } ?: PartyClassification.CUSTOMER

    fun toCommand(key: String, classification: PartyClassification = classification()): CreatePartyCommand {
        require(!email.isNullOrBlank()) { "email is required" }
        return CreatePartyCommand(
            key, PartyType.valueOf(partyType), legalName, tradingName,
            dateOfBirth, nationality, taxId, registrationNumber, email, phone, address?.toDomain(),
            id?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
            consentGdpr = consentGdpr,
            consentMarketing = consentMarketing,
            classification = classification,
        )
    }
}

/**
 * All fields optional; null leaves the stored value alone. `legalName`, `dateOfBirth` and
 * `nationality` are the MATERIAL master-data fields — editing one classifies the emitted
 * `PARTY_UPDATED` as MATERIAL (ADR-0256 D1, #4458).
 */
data class UpdatePartyRequest(
    val email: String?,
    val phone: String?,
    val tradingName: String?,
    val address: AddressRequest?,
    val legalName: String? = null,
    val dateOfBirth: String? = null,
    val nationality: String? = null,
)

/** ADR-0179. [approvalReference] links the merge-sweep transaction that swept the balances. */
data class MergePartyRequest(val mergedIntoPartyId: UUID, val reason: String, val approvalReference: String? = null)
data class UpdateConsentRequest(val marketingConsent: Boolean)
data class AddDocumentRequest(
    val documentType: String,
    val documentNumber: String,
    val issuingCountry: String,
    val expiryDate: String?,
)
data class KycStatusRequest(val kycStatus: String)
data class SavePayeeRequest(val name: String, val iban: String, val bic: String? = null)

fun Payee.toResponse() = mapOf(
    "id" to id,
    "name" to name,
    "iban" to iban,
    "bic" to bic,
    "createdAt" to createdAt,
)
data class AddressRequest(
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val countryCode: String,
) {
    fun toDomain() = Address(line1, line2, city, postalCode, countryCode)
}

fun Party.toSimpleResponse() = mapOf(
    "id" to id,
    "partyType" to partyType,
    "classification" to classification,
    "status" to status,
    "legalName" to legalName,
    "tradingName" to tradingName,
    "email" to email,
    "kycStatus" to kycStatus,
    "createdAt" to createdAt,
)

fun Party.toResponse() = mapOf(
    "id" to id,
    "partyType" to partyType,
    "classification" to classification,
    "status" to status,
    "legalName" to legalName,
    "tradingName" to tradingName, "email" to email, "phone" to phone,
    "kycStatus" to kycStatus, "address" to address, "createdAt" to createdAt, "updatedAt" to updatedAt,
    // Onboarding-time consent snapshot (consentGdpr is informational/non-revocable — see
    // UpdateMarketingConsentCommand kdoc) + the live, revocable marketing preference.
    "consentGdpr" to consentGdpr, "consentCapturedAt" to consentCapturedAt,
    "consentMarketing" to consentMarketing, "consentMarketingUpdatedAt" to consentMarketingUpdatedAt,
    // ADR-0179: non-null only on a MERGED party — tells a consumer holding a stale id which
    // party to follow instead.
    "mergedIntoPartyId" to mergedIntoPartyId,
)

fun PartyGdprExport.toResponse() = mapOf(
    "subject" to mapOf(
        "id" to party.id,
        "partyType" to party.partyType,
        "classification" to party.classification,
        "status" to party.status,
        "legalName" to party.legalName,
        "tradingName" to party.tradingName,
        "dateOfBirth" to party.dateOfBirth,
        "nationality" to party.nationality,
        "taxId" to party.taxId,
        "registrationNumber" to party.registrationNumber,
        "email" to party.email,
        "phone" to party.phone,
        "address" to party.address,
        "kycStatus" to party.kycStatus,
        "amlStatus" to party.amlStatus,
        "createdAt" to party.createdAt,
        "updatedAt" to party.updatedAt,
        // Consent state is PII the subject agreed to/withheld — belongs in an Art 15 access
        // export same as everything else here. Gap pre-dates this PR (never wired in when
        // consentGdpr/consentMarketing were added) — closing it now since it's adjacent.
        "consentGdpr" to party.consentGdpr,
        "consentCapturedAt" to party.consentCapturedAt,
        "consentMarketing" to party.consentMarketing,
        "consentMarketingUpdatedAt" to party.consentMarketingUpdatedAt,
    ),
    "documents" to documents.map {
        mapOf(
            "documentType" to it.documentType,
            "documentNumber" to it.documentNumber,
            "issuingCountry" to it.issuingCountry,
            "expiryDate" to it.expiryDate,
            "verifiedAt" to it.verifiedAt,
            "createdAt" to it.createdAt,
        )
    },
    "kyc" to kycData,
    "cards" to cardData,
    "exportedAt" to exportedAt,
    "scope" to GDPR_EXPORT_SCOPE,
)

private const val GDPR_EXPORT_SCOPE =
    "party-service direct PII and identity-document metadata. KYC PII (kyc-service) and " +
        "card PII (card-issuance-service) are held by those services; aggregating them into this " +
        "subject-access response is a tracked follow-up (ADR-0118 §6)."

/** Address-book hashes to match. See PhoneDirectory for what the hashing does and does not buy. */
data class DirectoryLookupRequest(
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     *
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of
     * a collection. So `{"phoneHashes": [null]}` deserialises happily into a `List<String>` holding
     * a null, and `PartyService.lookupByPhoneHashes` NPEs on `it.trim()`. Kotlin's non-null element
     * type was a compile-time promise nothing kept; writing the type honestly is what makes
     * [requireHashes] reachable instead of dead code.
     */
    val phoneHashes: List<String?> = emptyList(),
) {
    /**
     * The hashes, with every element proven present.
     *
     * A null ENTRY is a malformed JSON document, which is a different thing from the malformed
     * hash CONTENT the use case already tolerates by design (it silently drops anything that is not
     * 64 hex characters). `IllegalArgumentException` is mapped to 400 by libs-runtime's
     * `CommonExceptionMappers`; no service-local mapper is added (#526).
     */
    fun requireHashes(): List<String> = phoneHashes.mapIndexed { index, hash ->
        requireNotNull(hash) { "phoneHashes[$index] must not be null" }
    }
}

data class DiscoverableRequest(val discoverable: Boolean = false)

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNPROCESSABLE_ENTITY = 422
