// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.legalprocess.review

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

internal const val INBOX_VIEW = "ROLE_LEGAL_INBOX_VIEW"
internal const val INBOX_EVIDENCE = "ROLE_LEGAL_INBOX_EVIDENCE"
internal const val INBOX_ASSIGN = "ROLE_LEGAL_INBOX_ASSIGN"
internal const val MAILBOX_ROLE_PREFIX = "ROLE_LEGAL_MAILBOX_"

internal data class AssignmentRequest
@JsonCreator
constructor(
    @JsonProperty("team") val team: ReviewTeam?,
    @JsonProperty("expectedRevision") val expectedRevision: Int?,
)
internal data class InboxPage(val items: List<InboxReceipt>, val hasMore: Boolean)

@Path("/api/v1/legal-inbox")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(INBOX_VIEW)
internal class LegalInboxResource(private val review: PostgresInboxReview, private val identity: SecurityIdentity) {
    @GET
    fun list(@QueryParam("offset") @DefaultValue("0") offset: Int): Response {
        if (offset < 0) throw BadRequestException()
        val rows = review.list(operator(), offset)
        return privateResponse(Response.ok(InboxPage(rows.take(PAGE_SIZE), rows.size > PAGE_SIZE)))
    }

    @GET
    @Path("/{id}")
    fun detail(@PathParam("id") id: UUID): Response = handle {
        val detail = review.detail(id, operator(), identity.hasRole(INBOX_EVIDENCE), identity.hasRole(INBOX_ASSIGN))
        Response.ok(detail)
    }

    @GET
    @Path("/{id}/originals/{file}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    fun download(@PathParam("id") id: UUID, @PathParam("file") file: String?): Response = handle {
        val operator = operator(INBOX_EVIDENCE)
        val original = when (file) {
            "message" -> EvidenceFile.MESSAGE
            "delivery" -> EvidenceFile.DELIVERY
            else -> throw NotFoundException()
        }
        Response.ok(review.original(id, operator, original), MediaType.APPLICATION_OCTET_STREAM_TYPE)
            .header("Content-Disposition", "attachment; filename=\"isds-${original.name.lowercase()}.zfo\"")
    }

    @POST
    @Path("/{id}/extract")
    fun extract(@PathParam("id") id: UUID): Response = handle {
        review.extract(id, operator(INBOX_EVIDENCE))
        Response.ok(mapOf("status" to "ok"))
    }

    @POST
    @Path("/{id}/assignment")
    @Consumes(MediaType.APPLICATION_JSON)
    fun assign(@PathParam("id") id: UUID, request: AssignmentRequest?): Response = handle {
        val operator = operator(INBOX_ASSIGN)
        val team = request?.team ?: throw BadRequestException()
        val revision = request.expectedRevision?.takeIf { it >= 0 } ?: throw BadRequestException()
        review.assign(id, operator, team, revision)
        Response.ok(mapOf("status" to "ok"))
    }

    private fun operator(required: String = INBOX_VIEW): InboxOperator {
        if (!identity.hasRole(required)) throw ForbiddenException()
        val mailboxes = identity.roles.asSequence()
            .filter { it.startsWith(MAILBOX_ROLE_PREFIX) }
            .map { it.removePrefix(MAILBOX_ROLE_PREFIX) }
            .filter { MAILBOX.matches(it) }
            .map { "isds:$it" }
            .toList()
        val subject = identity.principal.name
        if (mailboxes.isEmpty() || subject.isBlank() || subject.length > MAX_SUBJECT_LENGTH) throw ForbiddenException()
        return InboxOperator(subject, mailboxes)
    }

    private fun handle(block: () -> Response.ResponseBuilder): Response {
        val response = try {
            block()
        } catch (_: ReceiptMissing) {
            Response.status(Response.Status.NOT_FOUND)
        } catch (_: RevisionConflict) {
            Response.status(Response.Status.CONFLICT)
        }
        return privateResponse(response)
    }

    private fun privateResponse(builder: Response.ResponseBuilder): Response = builder
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .build()

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_SUBJECT_LENGTH = 255
        val MAILBOX = Regex("[a-z0-9][a-z0-9._:-]{0,122}")
    }
}
