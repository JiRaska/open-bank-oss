// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/** Public, non-personal copy for the pre-login loader. Never expose the internal persona style. */
@Path("/customer/v1/app-copy")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
class AppCopyResource(
    private val upstream: UpstreamClient,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.edge.communication-service-url")
    private val serviceUrl: Optional<String>,
) {
    @GET
    @Blocking
    fun copy(): Response {
        val base = serviceUrl.orElse("").trimEnd('/')
        if (base.isBlank()) return Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
        upstream.get("$base/api/v1/personas/customer-copilot/published").use { response ->
            if (response.status == Response.Status.NOT_FOUND.statusCode) {
                // Retired/no published version explicitly clears previously cached overrides.
                return Response.ok(mapOf("version" to 0, "messages" to emptyMap<String, String>())).build()
            }
            if (response.status != Response.Status.OK.statusCode) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
            }
            val node = runCatching { mapper.readTree(response.entity as? String ?: "") }.getOrNull()
                ?: return Response.status(Response.Status.BAD_GATEWAY).build()
            val version = node.path("styleVersion")
            val messages = node.path("uiMessages")
            val validVersion = version.isInt && version.asInt() >= 1
            val validMessages = messages.isObject || messages.isMissingNode
            if (!validVersion || !validMessages) {
                return Response.status(Response.Status.BAD_GATEWAY).build()
            }
            return Response.ok(
                mapOf(
                    "version" to version.asInt(),
                    "messages" to if (messages.isObject) messages else emptyMap<String, String>(),
                ),
            ).header("Cache-Control", "public, max-age=60").build()
        }
    }
}
