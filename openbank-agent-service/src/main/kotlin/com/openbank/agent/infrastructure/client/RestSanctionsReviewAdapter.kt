// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.out.SanctionsReviewPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * REST adapter for [SanctionsReviewPort]. A 4xx/5xx from sanctions-service (403 when the grant is
 * missing, 409 on a stale check) is returned as an [SanctionsReviewPort.Outcome] rather than thrown,
 * so the tool can report the upstream's own reason to the model.
 */
@ApplicationScoped
class RestSanctionsReviewAdapter : SanctionsReviewPort {

    @Inject @RestClient
    lateinit var reviewClient: SanctionsReviewClient

    @Inject @RestClient
    lateinit var readClient: SanctionsServiceClient

    @Inject lateinit var objectMapper: ObjectMapper

    override fun getCheck(checkId: String): JsonNode = readClient.getCheck(checkId)

    override fun submitReview(
        checkId: String,
        newStatus: String,
        note: String,
        approvalId: String?,
        onBehalfOf: String,
    ): SanctionsReviewPort.Outcome {
        val body = mapOf("checkId" to checkId, "newStatus" to newStatus, "note" to note)
        val response = try {
            reviewClient.review(body, approvalId, onBehalfOf)
        } catch (e: WebApplicationException) {
            e.response
        }
        return response.use { SanctionsReviewPort.Outcome(it.status, readBody(it)) }
    }

    private fun readBody(response: Response): JsonNode? = runCatching {
        response.readEntity(String::class.java)?.takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
    }.getOrNull()
}
