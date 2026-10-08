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
            val projected = mutableMapOf<String, String>()
            if (messages.isObject) {
                val fields = messages.fields()
                while (fields.hasNext()) {
                    val (key, value) = fields.next()
                    if (!value.isTextual) return Response.status(Response.Status.BAD_GATEWAY).build()
                    val lead = value.asText()
                    val suffix = PROFILE_CHANGE_ACTIONS[key]
                    projected[key] = if (suffix == null) {
                        lead
                    } else {
                        if (
                            lead.isBlank() ||
                            lead.trim() != lead ||
                            lead.length > 120 ||
                            lead.last() !in setOf('.', '!', '?') ||
                            lead.count { it == '.' || it == '!' || it == '?' } != 1 ||
                            lead.any { it == '<' || it == '>' || it == '{' || it == '}' || it.isISOControl() }
                        ) {
                            return Response.status(Response.Status.BAD_GATEWAY).build()
                        }
                        "${lead.trim()} $suffix"
                    }
                }
            }
            return Response.ok(
                mapOf(
                    "version" to version.asInt(),
                    "messages" to projected,
                ),
            ).header("Cache-Control", "public, max-age=60").build()
        }
    }

    private companion object {
        val PROFILE_CHANGE_ACTIONS = mapOf(
            "cs.send.profileChanged" to "Zkontrolujte platbu a potvrďte ji znovu.",
            "en.send.profileChanged" to "Review and confirm the payment again.",
            "cs.so.err.profileChanged" to "Zkontrolujte příkaz a potvrďte ho znovu.",
            "en.so.err.profileChanged" to "Review and confirm the order again.",
        )
    }
}
