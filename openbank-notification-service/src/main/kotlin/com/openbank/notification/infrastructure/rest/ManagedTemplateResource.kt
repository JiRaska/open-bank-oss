// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.notification.domain.model.ManagedNotificationTemplate
import com.openbank.notification.domain.model.ManagedTemplateState
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.infrastructure.persistence.PgManagedTemplateStore
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
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
import java.util.UUID

data class ManagedTemplateDraftRequest(
    val template: NotificationTemplate,
    val language: NotificationLanguage,
    val channel: NotificationChannel,
    val subject: String,
    val body: String,
)

data class ManagedTemplatePreviewRequest(val draft: ManagedTemplateDraftRequest, val variables: Map<String, String?>)

/** Staff-only editorial surface. No customer or edge token can publish customer copy. */
@Path("/api/v1/notification-templates")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class ManagedTemplateResource(private val store: PgManagedTemplateStore, private val identity: SecurityIdentity) {
    private fun actor(): String = identity.principal?.name.orEmpty()

    @POST
    @Authorize(action = "commstemplate.create", resource = "")
    suspend fun create(request: ManagedTemplateDraftRequest): Response {
        val created = store.create(
            request.template,
            request.language,
            request.channel,
            request.subject,
            request.body,
            actor(),
        )
        return Response.status(Response.Status.CREATED).entity(created).build()
    }

    @GET
    @Authorize(action = "commstemplate.read", resource = "")
    suspend fun list(@QueryParam("template") template: NotificationTemplate?): Response {
        template ?: return Response.status(Response.Status.BAD_REQUEST).build()
        return Response.ok(store.list(template)).build()
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "commstemplate.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): Response = store.find(id)?.let { Response.ok(it).build() }
        ?: Response.status(Response.Status.NOT_FOUND).build()

    @POST
    @Path("/preview")
    @Authorize(action = "commstemplate.preview", resource = "")
    fun preview(request: ManagedTemplatePreviewRequest): Response {
        val draft = request.draft
        val copy = ManagedNotificationTemplate(
            Ids.newId(), draft.template, draft.language, draft.channel, 0,
            draft.subject, draft.body, ManagedTemplateState.DRAFT, actor(), Instant.now(),
        )
        val variables = request.variables.mapValues { (name, value) ->
            requireNotNull(value) { "preview variable '$name' must not be null" }
        }
        val (subject, body) = copy.render(variables)
        return Response.ok(mapOf("subject" to subject, "body" to body)).build()
    }

    @POST
    @Path("/{id}/publish")
    @Consumes(MediaType.WILDCARD)
    @Authorize(action = "commstemplate.publish", resource = "#id")
    suspend fun publish(@PathParam("id") id: UUID): Response {
        val draft = store.find(id) ?: return Response.status(Response.Status.NOT_FOUND).build()
        if (draft.createdBy == actor()) {
            return Response.status(Response.Status.CONFLICT)
                .entity(mapOf("error" to "a different operator must publish the revision")).build()
        }
        return store.publish(id, actor())?.let { Response.ok(it).build() }
            ?: Response.status(Response.Status.CONFLICT).build()
    }
}
