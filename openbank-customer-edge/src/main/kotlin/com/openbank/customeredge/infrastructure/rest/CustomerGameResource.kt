// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Party comes only from the verified customer identity. Beta is a presentation hint, not an entitlement. */
@Path("/customer/v1/game/levels")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerGameResource(
    private val upstream: UpstreamClient,
    private val parties: CustomerPartyResolver,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.edge.engagement-service-url") private val serviceUrl: String,
) {
    @GET
    @Blocking
    @Authorize(action = "customer.game.read")
    fun levels(@QueryParam("beta") beta: Boolean, @HeaderParam("X-Acting-For") actingFor: String?): Response {
        val party = parties.resolve(actingFor).toString()
        upstream.get("${serviceUrl.trimEnd('/')}/api/v1/game/levels?partyId=$party&beta=$beta", party).use { response ->
            if (response.status !=
                Response.Status.OK.statusCode
            ) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(
                    mapOf(
                        "code" to "GAME_UNAVAILABLE",
                    ),
                ).build()
            }
            val node = runCatching { mapper.readTree(response.entity as? String ?: "") }.getOrNull()
                ?: return Response.status(Response.Status.BAD_GATEWAY).build()
            if (!node.path("revision").canConvertToLong() ||
                !node.path("levels").isArray ||
                node.path("levels").size() > MAX_LEVELS
            ) {
                return Response.status(Response.Status.BAD_GATEWAY).build()
            }
            val levels = node.path("levels").map { entry ->
                val level = entry.path("level")
                val validIdentity =
                    level.path("id").isTextual && level.path("theme").isTextual && level.path("order").isInt
                val validState = entry.path("unlocked").isBoolean && entry.path("completed").isBoolean
                if (!validIdentity || !validState) return Response.status(Response.Status.BAD_GATEWAY).build()
                buildMap<String, Any> {
                    for (key in listOf("id", "theme", "order", "titleCs", "titleEn", "state")) put(key, level.path(key))
                    put("requirement", level.path("requirement"))
                    put("unlocked", entry.path("unlocked"))
                    put("completed", entry.path("completed"))
                }
            }
            return Response.ok(
                mapOf("revision" to node.path("revision"), "levels" to levels),
            ).header("Cache-Control", "no-store").build()
        }
    }

    @POST
    @Path("/{levelId}/completion")
    @Blocking
    @Authorize(action = "customer.game.complete")
    fun complete(
        @PathParam("levelId") levelId: String,
        @QueryParam("beta") beta: Boolean,
        @HeaderParam("X-Acting-For") actingFor: String?,
    ): Response {
        if (!levelId.matches(Regex("[a-z][a-z0-9_]{0,63}"))) return Response.status(Response.Status.BAD_REQUEST).build()
        val party = parties.resolve(actingFor).toString()
        upstream.post(
            "${serviceUrl.trimEnd('/')}/api/v1/game/levels/$levelId/completion?partyId=$party&beta=$beta",
            party,
            "{}",
        ).use { response ->
            return Response.status(
                when (response.status) {
                    Response.Status.NO_CONTENT.statusCode -> Response.Status.NO_CONTENT.statusCode
                    Response.Status.FORBIDDEN.statusCode -> Response.Status.FORBIDDEN.statusCode
                    else -> Response.Status.SERVICE_UNAVAILABLE.statusCode
                },
            ).build()
        }
    }
    private companion object {
        const val MAX_LEVELS = 30
    }
}
