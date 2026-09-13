// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * communication-service's D5 consumer-facing read (ADR-0285). Service-to-service, client-credentials
 * token — the question ("what does this persona's published style say") is not tied to any one
 * customer's bearer, mirrors `ConsentQueryClient`'s reasoning for the same filter choice.
 */
@RegisterRestClient(configKey = "communication-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/personas")
@Produces(MediaType.APPLICATION_JSON)
interface CommunicationStyleClient {
    @GET
    @Path("/{personaKey}/published")
    @Timeout(PUBLISHED_STYLE_TIMEOUT_MS)
    fun getPublishedStyle(@PathParam("personaKey") personaKey: String): Uni<PublishedStyleDto>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PublishedStyleDto(
    val personaKey: String = "",
    val styleVersion: Int = 0,
    val tone: String = "",
    val formality: String = "",
    val formOfAddress: String = "",
    val maxLength: Int? = null,
    val preferredTerms: Map<String, String> = emptyMap(),
    val forbiddenTerms: List<String> = emptyList(),
    val signature: String? = null,
)

private const val PUBLISHED_STYLE_TIMEOUT_MS = 2000L
