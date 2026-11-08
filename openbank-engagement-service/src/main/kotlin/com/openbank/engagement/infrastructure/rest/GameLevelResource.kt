// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.infrastructure.rest

import com.openbank.engagement.application.port.out.GameStore
import com.openbank.engagement.domain.model.game.GameCatalogConflict
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.util.UUID

@Path("/api/v1/game")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class GameLevelResource(
    private val store: GameStore,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/admin/catalog")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_MARKETING")
    @Authorize(action = "engagement.gameCatalog.read")
    suspend fun catalog(): Response = Response.ok(store.catalog()).header("Cache-Control", "no-store").build()

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/admin/catalog")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "engagement.gameCatalog.write")
    suspend fun update(request: GameCatalogWriteRequest): Response = save(request, false)

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/admin/drafts")
    @RolesAllowed("ROLE_MARKETING", "ROLE_ADMIN")
    @Authorize(action = "engagement.gameCatalog.writeDraft")
    suspend fun updateDraft(request: GameCatalogWriteRequest): Response = save(request, true)

    private suspend fun save(request: GameCatalogWriteRequest, draftOnly: Boolean): Response = try {
        val catalog = request.toDomain()
        // update rechecks the same revision under its row lock; a concurrent publisher causes 409.
        if (draftOnly) catalog.requireDraftEditOf(store.catalog())
        val actor = requireNotNull(identity.principal.name).also { require(it.isNotBlank()) }
        Response.ok(store.update(catalog, actor, clock.instant())).build()
    } catch (_: GameCatalogConflict) {
        Response.status(Response.Status.CONFLICT).entity(mapOf("code" to "CATALOG_CHANGED")).build()
    }

    @GET
    @Path("/admin/audit")
    @RolesAllowed("ROLE_ADMIN", "ROLE_COMPLIANCE")
    @Authorize(action = "engagement.gameCatalog.read")
    suspend fun audit(): Response = Response.ok(store.audit()).header("Cache-Control", "no-store").build()

    @GET
    @Path("/levels")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "engagement.game.read", resource = "#partyId")
    suspend fun levels(
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("beta") beta: Boolean,
        @QueryParam("contentSchema") contentSchema: Int?,
    ): Response = Response.ok(
        store.customer(requireNotNull(partyId), beta, clock.instant()).let { catalog ->
            if (contentSchema ==
                1
            ) {
                catalog
            } else {
                catalog.copy(
                    levels = catalog.levels.mapNotNull { entry ->
                        val level = entry.level
                        when {
                            level.content == null -> entry
                            level.id == level.theme && level.id in setOf("embankment", "rooftops", "charles_bridge") ->
                                entry.copy(level = level.copy(content = null))
                            else -> null
                        }
                    },
                )
            }
        },
    ).header("Cache-Control", "no-store").build()

    @POST
    @Path("/levels/{levelId}/completion")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "engagement.game.complete", resource = "#partyId")
    suspend fun complete(
        @PathParam("levelId") levelId: String,
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("beta") beta: Boolean,
    ): Response {
        val accepted = store.complete(requireNotNull(partyId), levelId, beta, clock.instant())
        return if (accepted) Response.noContent().build() else Response.status(Response.Status.FORBIDDEN).build()
    }
}
