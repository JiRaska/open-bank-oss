// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import com.openbank.audit.application.AuditAnchorService
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Audit")
class AuditResource {

    @Inject lateinit var repo: AuditRepository

    @Inject lateinit var anchors: AuditAnchorService

    companion object {
        /** Upper bound on a customer-facing page; mirrors the access-log cap. */
        private const val MAX_CUSTOMER_PAGE = 500

        private val operationJson = com.fasterxml.jackson.databind.ObjectMapper()
    }

    @GET
    @Path("/customer/{partyId}")
    // Customer-facing privacy view (P2-27): the customer edge proxies a caller's OWN access
    // trail here, injecting partyId from the JWT so a client-supplied id never reaches this
    // path. Roles mirror the edge-proxied precedent (document-service SignatureCeremonyResource)
    // because they have to: UpstreamClient authenticates as the `openbank-edge` client, whose
    // service account carries ROLE_OPERATOR — @RolesAllowed(ROLE_API) alone 403'd every call
    // before OPA was ever consulted. ROLE_OPERATOR is also held by real staff, so the narrowing
    // is OPA's job: `audit.customerRead` is a DISTINCT action from the auditor-facing
    // `audit.read`, granted by rest.rego's `edge-service-audit-customer` rule to exactly the
    // `service-account-openbank-edge` principal id and nothing else. The payload is deliberately
    // not projected: the app gets event metadata, not event internals.
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "audit.customerRead", resource = "#partyId")
    @Operation(summary = "Get a party's own access log (customer privacy view)")
    suspend fun getCustomerAccessLog(
        @PathParam("partyId") partyId: String,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
    ): Response {
        val entries = repo.findByAggregateId(partyId, limit.coerceIn(1, 500)).map {
            mapOf(
                "eventType" to it.eventType,
                "aggregateType" to it.aggregateType,
                "actorType" to it.actorType,
                "sourceService" to it.sourceService,
                "occurredAt" to it.occurredAt.toString(),
            )
        }
        return Response.ok(entries).build()
    }

    /**
     * "What did they do with my account" — the grantor transparency query (ADR-0232 D5, #2990
     * AC10). Every action a delegate took ON BEHALF OF [grantorPartyId], newest first.
     *
     * Same edge-proxy gate and the same `audit.customerRead` OPA action as the customer access
     * log above, for the same reason and with the same contract: the edge injects the party from
     * the JWT so a client-supplied id never reaches this path, and `rest.rego` grants the action
     * to exactly `service-account-openbank-edge`. Reusing that action rather than minting one
     * keeps a policy-bundle restamp off a change that does not need it — the cost is that any
     * principal already able to read a party's own access log can read this too. Those are the
     * same principal set today; if they ever diverge this needs its own action.
     *
     * Projected, not raw: the grantor is entitled to know *that* their delegate paid, when, and
     * under which grant — not to the full event payload, which is regulated evidence for the
     * auditor-gated routes below. `actorId` (the delegate) is included because withholding it
     * would defeat the point of the view.
     */
    @GET
    @Path("/on-behalf-of/{grantorPartyId}")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "audit.customerRead", resource = "#grantorPartyId")
    @Operation(
        summary = "Actions taken on behalf of a grantor by their delegates (ADR-0232 D5)",
        description = "The data behind \"what did they do with my account\": every audited action " +
            "whose on-behalf-of party is this grantor. Optionally narrowed to one delegate " +
            "(?delegatePartyId=) or one grant (?delegationId=).",
    )
    suspend fun getDelegatedActionsForGrantor(
        @PathParam("grantorPartyId") grantorPartyId: String,
        @QueryParam("delegatePartyId") delegatePartyId: String?,
        @QueryParam("delegationId") delegationId: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
    ): Response {
        val entries = repo.findOnBehalfOf(
            grantorPartyId = grantorPartyId,
            delegatePartyId = delegatePartyId?.takeIf { it.isNotBlank() },
            delegationId = delegationId?.takeIf { it.isNotBlank() },
            limit = limit.coerceIn(1, MAX_CUSTOMER_PAGE),
        ).map {
            mapOf(
                "entryId" to it.id.toString(),
                "eventType" to it.eventType,
                "operation" to extractOperation(it.payload),
                "delegatePartyId" to it.actorId,
                "delegationId" to it.delegationId,
                "resourceType" to it.aggregateType,
                "sourceService" to it.sourceService,
                "occurredAt" to it.occurredAt.toString(),
            )
        }
        return Response.ok(entries).build()
    }

    /**
     * The customer-facing verb ("payments.domestic"), read out of the chain-hashed payload rather
     * than stored beside it. A second copy of a field that already exists in the payload is a
     * field that can disagree with it; this projection reads the evidence.
     */
    private fun extractOperation(payload: String): String? =
        runCatching { operationJson.readTree(payload)["operation"]?.asText() }.getOrNull()

    @GET
    @Path("/entries/{aggregateId}")
    // The audit trail is regulated evidence (SOX/DORA): read-only and role-gated.
    // AUDITOR is the dedicated read-only audit role; ADMIN and COMPLIANCE also need it
    // for investigations. Never @PermitAll — an unauthenticated audit log is itself an
    // audit finding (K7).
    @RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "audit.read", resource = "#aggregateId")
    @Operation(summary = "Get audit trail for an aggregate (account, party, transaction, etc.)")
    suspend fun getAuditTrail(
        @PathParam("aggregateId") aggregateId: String,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
    ): Response = Response.ok(repo.findByAggregateId(aggregateId, limit.coerceIn(1, 500))).build()

    @GET
    @Path("/entries/by-actor/{actorId}")
    // Same regulated-evidence gate as the aggregate trail above; JAX-RS prefers this literal
    // "by-actor" segment over the {aggregateId} template, so the two routes never collide.
    @RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "audit.read", resource = "#actorId")
    @Operation(
        summary = "Get audit entries for one actor across all ingress channels (ADR-0226) — " +
            "the forensic 'what did person X do' query, optionally narrowed by ?channel=ui|mcp|api",
    )
    suspend fun getActorTrail(
        @PathParam("actorId") actorId: String,
        @QueryParam("channel") channel: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
    ): Response = Response.ok(repo.findByActorId(actorId, channel, limit.coerceIn(1, 500))).build()

    /**
     * Re-walk the hash chain and report whether it is intact (ADR-0086 / DORA Art. 17).
     *
     * The chain is computed over all `audit_entries` rows ordered by insertion sequence.
     * Any in-place edit, deletion, or re-ordering of rows produces a recomputation
     * mismatch at the first affected row, reported as `chainStatus: "BROKEN"` plus the
     * `firstBrokenAt` entry id.
     *
     * When [fromEventId] is supplied the walk starts at that entry (inclusive) using its
     * own `prevHash` as the anchor, so incident responders can verify a specific tail of
     * the log without re-walking the entire history.
     *
     * Response:
     * ```json
     * {
     *   "chainStatus": "INTACT" | "BROKEN",
     *   "checkedCount": 1234,
     *   "unchainedCount": 0,
     *   "firstBrokenAt": null | "<uuid>"
     * }
     * ```
     */
    @GET
    @Path("/integrity")
    @RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "audit.verify", resource = "")
    @Operation(
        summary = "Verify the audit hash chain (ADR-0086)",
        description = "Recomputes every SHA-256 link end-to-end. Returns BROKEN and the first " +
            "broken entry id if any row was edited, deleted or re-ordered. Supply fromEventId " +
            "to verify only the tail of the chain from a known-good anchor.",
    )
    suspend fun verifyIntegrity(
        @Parameter(
            description = "Start the integrity walk at this entry_id (inclusive). " +
                "Omit to walk the full chain from the genesis block.",
            schema = Schema(implementation = UUID::class),
        )
        @QueryParam("fromEventId") fromEventId: String?,
    ): Response {
        val anchor = fromEventId?.let {
            runCatching { UUID.fromString(it.trim()) }.getOrNull()
                ?: return Response.status(400)
                    .entity("""{"error":"fromEventId must be a valid UUID"}""")
                    .type(MediaType.APPLICATION_JSON)
                    .build()
        }
        val result = repo.verifyChain(anchor)
        val body = IntegrityResponse(
            chainStatus = if (result.intact) "INTACT" else "BROKEN",
            checkedCount = result.checked,
            unchainedCount = result.unchained,
            firstBrokenAt = result.firstBrokenEntryId,
        )
        return Response.ok(body).build()
    }

    /**
     * List recent signed anchors (ADR-0031 D5). Each anchor is an externally-signed checkpoint
     * over the chain head at a point in time.
     */
    @GET
    @Path("/anchors")
    @RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "audit.verify", resource = "")
    @Operation(summary = "List recent signed audit anchors (ADR-0031 D5)")
    suspend fun listAnchors(@QueryParam("limit") @DefaultValue("50") limit: Int): Response =
        Response.ok(anchors.recent(limit)).build()

    /**
     * Verify every signed anchor: recompute each digest, check its signature, and confirm the
     * attested chain head still matches the live chain. Detects a wholesale rewrite of the log
     * that the internal hash-chain walk in [verifyIntegrity] alone cannot (ADR-0031 D5).
     */
    @GET
    @Path("/anchors/verify")
    @RolesAllowed("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "audit.verify", resource = "")
    @Operation(
        summary = "Verify all signed audit anchors (ADR-0031 D5)",
        description = "Recomputes each anchor digest, verifies its signature, and confirms the " +
            "attested head hash still matches the live chain. Returns BROKEN with the first " +
            "failing anchor if a signature is invalid or the chain was rewritten.",
    )
    suspend fun verifyAnchors(): Response = Response.ok(anchors.verifyAnchors()).build()
}

/**
 * Wire-format for `GET /api/v1/audit/integrity` (ADR-0086).
 *
 * Fields:
 * - chainStatus: "INTACT" when every recomputed link matches; "BROKEN" on the first mismatch.
 * - checkedCount: number of chained rows verified (rows without record_hash excluded).
 * - unchainedCount: rows written before the hash-chain migration (V5) — counted, not verifiable.
 * - firstBrokenAt: entry_id of the first broken link; null when chainStatus is INTACT.
 */
data class IntegrityResponse(
    val chainStatus: String,
    val checkedCount: Long,
    val unchainedCount: Long,
    val firstBrokenAt: UUID?,
)
