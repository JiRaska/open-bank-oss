// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.finrep.application.port.inbound.RecordAcknowledgementCommand
import com.openbank.finrep.application.port.inbound.SupervisorySubmissionUseCase
import com.openbank.finrep.domain.model.SubmissionAttempt
import com.openbank.finrep.domain.model.SubmissionKey
import com.openbank.finrep.domain.model.SupervisorySubmission
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OpenSubmissionRequest(
    val templateId: String? = null,
    val referencePeriod: String? = null,
    val revision: Int? = null,
)

data class AcknowledgementRequest(
    val accepted: Boolean? = null,
    val reference: String? = null,
    val message: String? = null,
)

data class SubmissionResponse(
    val id: UUID,
    val templateId: String,
    val referencePeriod: LocalDate,
    val revision: Int,
    val state: String,
    val createdBy: String,
    val createdAt: Instant,
    val reportingFrameworkVersion: String?,
    val taxonomyVersion: String?,
    val packageHash: String?,
    val packagedAt: Instant?,
    val submittedAt: Instant?,
    val transportReference: String?,
    val acknowledgementAccepted: Boolean?,
    val acknowledgementReference: String?,
    val acknowledgedAt: Instant?,
)

data class SubmissionAttemptResponse(
    val attemptNo: Int,
    val transport: String,
    val outcome: String,
    val transportReference: String?,
    val detail: String?,
    val attemptedBy: String,
    val attemptedAt: Instant,
)

/**
 * Supervisory return submission lifecycle. The default transport is a dry run: `submit` records a
 * `DRY_RUN` attempt and leaves the return PACKAGED, because nothing was transmitted.
 */
@ApplicationScoped
@Path("/api/v1/finrep/submissions")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, Roles.OPERATOR)
class SupervisorySubmissionResource(
    private val useCase: SupervisorySubmissionUseCase,
    private val identity: SecurityIdentity,
) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun open(request: OpenSubmissionRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val key = SubmissionKey(
            templateId = requireNotNull(body.templateId) { "templateId is required" },
            referencePeriod = LocalDate.parse(requireNotNull(body.referencePeriod) { "referencePeriod is required" }),
            revision = requireNotNull(body.revision) { "revision is required" },
        )
        val (submission, created) = useCase.open(key, actor())
        val status = if (created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status).entity(submission.toResponse()).build()
    }

    @GET
    suspend fun list(@QueryParam("templateId") templateId: String?): List<SubmissionResponse> =
        useCase.list(templateId?.takeIf { it.isNotBlank() }).map { it.toResponse() }

    @GET
    @Path("/{id}")
    suspend fun get(@PathParam("id") id: UUID): SubmissionResponse = useCase.get(id).toResponse()

    @POST
    @Path("/{id}/package")
    suspend fun pkg(@PathParam("id") id: UUID): SubmissionResponse = useCase.pkg(id, actor()).toResponse()

    @POST
    @Path("/{id}/submit")
    suspend fun submit(@PathParam("id") id: UUID): SubmissionResponse = useCase.submit(id, actor()).toResponse()

    @POST
    @Path("/{id}/acknowledgement")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun acknowledge(@PathParam("id") id: UUID, request: AcknowledgementRequest?): SubmissionResponse {
        val body = requireNotNull(request) { "request body is required" }
        val command = RecordAcknowledgementCommand(
            accepted = requireNotNull(body.accepted) { "accepted is required" },
            reference = requireNotNull(body.reference) { "reference is required" },
            message = body.message,
        )
        return useCase.acknowledge(id, command, actor()).toResponse()
    }

    @GET
    @Path("/{id}/attempts")
    suspend fun attempts(@PathParam("id") id: UUID): List<SubmissionAttemptResponse> =
        useCase.attempts(id).map { it.toResponse() }

    private fun actor(): String = identity.principal?.name ?: "unknown"
}

internal fun SupervisorySubmission.toResponse() = SubmissionResponse(
    id = id,
    templateId = key.templateId,
    referencePeriod = key.referencePeriod,
    revision = key.revision,
    state = state.name,
    createdBy = createdBy,
    createdAt = createdAt,
    reportingFrameworkVersion = reportingFrameworkVersion,
    taxonomyVersion = taxonomyVersion,
    packageHash = packageHash,
    packagedAt = packagedAt,
    submittedAt = submittedAt,
    transportReference = transportReference,
    acknowledgementAccepted = acknowledgement?.accepted,
    acknowledgementReference = acknowledgement?.reference,
    acknowledgedAt = acknowledgement?.recordedAt,
)

internal fun SubmissionAttempt.toResponse() = SubmissionAttemptResponse(
    attemptNo = attemptNo,
    transport = transport,
    outcome = outcome.name,
    transportReference = transportReference,
    detail = detail,
    attemptedBy = attemptedBy,
    attemptedAt = attemptedAt,
)
