// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.ProposeReferenceCommand
import com.openbank.treasury.application.port.`in`.ReferenceActivity
import com.openbank.treasury.application.port.`in`.ReferenceDataUseCase
import com.openbank.treasury.application.port.`in`.ReferenceDependant
import com.openbank.treasury.application.port.`in`.ReferenceHistory
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.infrastructure.reference.ReferencePayloadCodec
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import java.time.Instant
import java.time.LocalDate

/**
 * Effective-dated, four-eyes reference data (ADR-0344 D3). RBAC here, OPA
 * (`treasury_rest_ext.rego`) behind it, and the domain's four-eyes / human-only checks behind
 * both. Path parameters are Strings and parsed in the body: a JAX-RS enum conversion failure would
 * be a 404, not the 400 an unknown kind is.
 */
@Tag(name = "Treasury", description = "Treasury reference data (ADR-0344 D3)")
@Path("/api/v1/treasury/reference")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE, REFERENCE_EDITOR)
@Suppress("TooManyFunctions") // one endpoint per contract operation
class ReferenceResource {

    @Inject
    lateinit var references: ReferenceDataUseCase

    @Inject
    lateinit var codec: ReferencePayloadCodec

    @Inject
    lateinit var identity: SecurityIdentity

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private fun requireKey(key: String?): String = requireNotNull(key) { "header '$IDEMPOTENCY_KEY' is required" }

    private fun version(raw: String): Int = requireNotNull(raw.toIntOrNull()?.takeIf { it >= 1 }) {
        "version must be a positive integer"
    }

    @GET
    @Path("/{kind}")
    @Operation(summary = "Reference records of one kind, optionally effective on asOf and/or in one status")
    @Authorize(action = "treasury.reference.read")
    suspend fun list(
        @PathParam("kind") kind: String,
        @QueryParam("asOf") asOf: LocalDate?,
        @QueryParam("status") status: String?,
    ): List<ReferenceRecordResponse> = references.list(
        ReferenceKind.fromWire(kind),
        status?.let { s ->
            requireNotNull(ReferenceStatus.entries.firstOrNull { it.name == s }) {
                "status must be one of ${ReferenceStatus.entries}"
            }
        },
        asOf,
    ).map { ReferenceRecordResponse.from(it, codec) }

    @GET
    @Path("/{kind}/{key}")
    @Operation(summary = "The active version of one record and its full history")
    @Authorize(action = "treasury.reference.read")
    suspend fun history(@PathParam("kind") kind: String, @PathParam("key") key: String): ReferenceHistoryResponse =
        ReferenceHistoryResponse.from(references.history(ReferenceKind.fromWire(kind), key), codec)

    @POST
    @Path("/{kind}")
    @RolesAllowed(REFERENCE_EDITOR, BACK_OFFICE)
    @Operation(summary = "Propose a new version (PENDING until a different human approves)")
    @Authorize(action = "treasury.reference.propose")
    suspend fun propose(
        @PathParam("kind") kind: String,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReferenceProposalRequest,
    ): Response {
        val k = ReferenceKind.fromWire(kind)
        val record = references.propose(
            ProposeReferenceCommand(
                kind = k,
                key = requireNotNull(request.key) { "key is required" },
                validFrom = requireNotNull(request.validFrom) { "validFrom is required" },
                validTo = request.validTo,
                payload = codec.read(k, requireNotNull(request.payload) { "payload is required" }),
                reason = requireNotNull(request.reason) { "reason is required" },
            ),
            actor(),
            requireKey(key),
        )
        return Response.status(Response.Status.CREATED).entity(ReferenceRecordResponse.from(record, codec)).build()
    }

    @POST
    @Path("/{kind}/{key}/versions/{version}/approve")
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Approve a PENDING version; supersedes the overlapping ACTIVE one (422 four-eyes)")
    @Authorize(action = "treasury.reference.approve", resource = "#kind")
    suspend fun approve(
        @PathParam("kind") kind: String,
        @PathParam("key") recordKey: String,
        @PathParam("version") version: String,
        @HeaderParam("Idempotency-Key") key: String?,
    ): ReferenceRecordResponse = ReferenceRecordResponse.from(
        references.approve(
            ReferenceKind.fromWire(kind),
            recordKey,
            version(version),
            actor(),
            requireKey(key),
            identity.hasRole(SENIOR_APPROVER),
        ),
        codec,
    )

    @POST
    @Path("/{kind}/{key}/versions/{version}/reject")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reject a PENDING version with a reason")
    @Authorize(action = "treasury.reference.reject")
    suspend fun reject(
        @PathParam("kind") kind: String,
        @PathParam("key") recordKey: String,
        @PathParam("version") version: String,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): ReferenceRecordResponse = ReferenceRecordResponse.from(
        references.reject(
            ReferenceKind.fromWire(kind),
            recordKey,
            version(version),
            requireNotNull(request.reason) { "reason is required" },
            actor(),
            requireKey(key),
        ),
        codec,
    )

    @POST
    @Path("/{kind}/{key}/versions/{version}/verify")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Mark a version verified against its source (not its proposer, never an agent)")
    @Authorize(action = "treasury.reference.verify")
    suspend fun verify(
        @PathParam("kind") kind: String,
        @PathParam("key") recordKey: String,
        @PathParam("version") version: String,
        @HeaderParam("Idempotency-Key") key: String?,
    ): ReferenceRecordResponse = ReferenceRecordResponse.from(
        references.verify(ReferenceKind.fromWire(kind), recordKey, version(version), actor(), requireKey(key)),
        codec,
    )

    @GET
    @Path("/{kind}/export")
    @Operation(summary = "Every version of every record of one kind, in the GET shape")
    @Authorize(action = "treasury.reference.read")
    suspend fun export(@PathParam("kind") kind: String): List<ReferenceRecordResponse> =
        references.list(ReferenceKind.fromWire(kind), null, null).map { ReferenceRecordResponse.from(it, codec) }

    @POST
    @Path("/{kind}/import")
    @RolesAllowed(REFERENCE_EDITOR, BACK_OFFICE)
    @Operation(summary = "Propose many versions at once; all-or-nothing; each PENDING until four-eyes approval")
    @Authorize(action = "treasury.reference.import")
    suspend fun import(
        @PathParam("kind") kind: String,
        @HeaderParam("Idempotency-Key") key: String?,
        request: List<ReferenceProposalRequest>?,
    ): Response {
        val k = ReferenceKind.fromWire(kind)
        val items = requireNotNull(request) { "a JSON array of proposals is required" }
        val commands = items.mapIndexed { i, r ->
            try {
                ProposeReferenceCommand(
                    kind = k,
                    key = requireNotNull(r.key) { "key is required" },
                    validFrom = requireNotNull(r.validFrom) { "validFrom is required" },
                    validTo = r.validTo,
                    payload = codec.read(k, requireNotNull(r.payload) { "payload is required" }),
                    reason = requireNotNull(r.reason) { "reason is required" },
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("item $i: ${e.message}", e)
            }
        }
        val created = references.importProposals(k, commands, actor(), requireKey(key))
        return Response.status(Response.Status.CREATED).entity(
            created.map {
                ReferenceRecordResponse.from(it, codec)
            },
        ).build()
    }

    @GET
    @Path("/user-activity")
    @Operation(summary = "Read-only: who proposed, approved, rejected or verified reference data, newest first")
    @Authorize(action = "treasury.reference.read")
    suspend fun activity(@QueryParam("since") since: Instant?): List<ReferenceActivity> = references.activity(since)

    @GET
    @Path("/application-version-history")
    @Operation(summary = "Read-only: the schema migrations applied to this service's store")
    @Authorize(action = "treasury.reference.read")
    suspend fun versionHistory(): List<Map<String, Any?>> = Panache.getSession().flatMap { s ->
        s.createNativeQuery<Array<Any?>>(
            "select version, description, installed_on from flyway_schema_history where success order by installed_rank",
        ).resultList
    }.awaitSuspending().map { row: Array<Any?> ->
        mapOf<String, Any?>(
            "version" to row[0]?.toString(),
            "description" to row[1]?.toString(),
            "installedOn" to row[2]?.toString(),
        )
    }

    @GET
    @Path("/dependencies/{kind}/{key}")
    @Operation(summary = "What references this record: deals and other reference records")
    @Authorize(action = "treasury.reference.read")
    suspend fun dependencies(
        @PathParam("kind") kind: String,
        @PathParam("key") key: String,
    ): ReferenceDependenciesResponse {
        val k = ReferenceKind.fromWire(kind)
        return ReferenceDependenciesResponse(k.wire, key, references.dependencies(k, key))
    }
}

data class ReferenceProposalRequest(
    val key: String? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val payload: JsonNode? = null,
    val reason: String? = null,
)

data class ReferenceRecordResponse(
    val kind: String,
    val key: String,
    val version: Int,
    val status: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val verified: Boolean,
    val payload: JsonNode,
    val createdBy: String,
    val createdByType: String,
    val createdAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val verifiedBy: String?,
    val reason: String?,
    val decisionReason: String?,
    val approvalNote: String?,
) {
    companion object {
        fun from(r: ReferenceRecord, codec: ReferencePayloadCodec) = ReferenceRecordResponse(
            kind = r.kind.wire,
            key = r.key,
            version = r.version,
            status = r.status.name,
            validFrom = r.validFrom,
            validTo = r.validTo,
            verified = r.verified,
            payload = codec.tree(r.payload),
            createdBy = r.createdBy.id,
            createdByType = r.createdBy.type.name,
            createdAt = r.createdAt,
            approvedBy = r.approvedBy?.id,
            approvedAt = r.approvedAt,
            verifiedBy = r.verifiedBy?.id,
            reason = r.reason,
            decisionReason = r.decisionReason,
            approvalNote = r.approvalNote,
        )
    }
}

data class ReferenceHistoryResponse(
    val kind: String,
    val key: String,
    val active: ReferenceRecordResponse?,
    val versions: List<ReferenceRecordResponse>,
) {
    companion object {
        fun from(h: ReferenceHistory, codec: ReferencePayloadCodec) = ReferenceHistoryResponse(
            kind = h.kind.wire,
            key = h.key,
            active = h.active?.let { ReferenceRecordResponse.from(it, codec) },
            versions = h.versions.map { ReferenceRecordResponse.from(it, codec) },
        )
    }
}

data class ReferenceDependenciesResponse(val kind: String, val key: String, val dependants: List<ReferenceDependant>)
