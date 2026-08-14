// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/AGPL-3.0-only.txt for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.CatalogReviewService
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.util.UUID

/** Human-triggered, read-only catalog review. Its only persistent output is a PROPOSED HITL item. */
@Path("/agent/catalog-reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")
class CatalogReviewResource {

    @Inject lateinit var reviews: CatalogReviewService

    data class ReviewRequest(val offeringId: String?, val revisionId: String?, val model: String? = null)
    data class FindingDto(
        val severity: String,
        val category: String,
        val instancePath: String,
        val evidence: String,
        val recommendation: String,
        val requiresHumanDecision: Boolean,
    )
    data class ResponseDto(
        val proposalId: String,
        val state: String,
        val contextHash: String,
        val summary: String,
        val findings: List<FindingDto>,
        val model: String,
    )

    @POST
    fun review(body: ReviewRequest): Response {
        val offeringId = validUuid(body.offeringId) ?: return badRequest("offeringId must be a UUID")
        val revisionId = validUuid(body.revisionId) ?: return badRequest("revisionId must be a UUID")
        return try {
            val result = runBlocking { reviews.review(offeringId, revisionId, body.model) }
            Response.status(Response.Status.CREATED).entity(
                ResponseDto(
                    proposalId = result.proposal.id.toString(),
                    state = result.proposal.state.name,
                    contextHash = result.contextHash,
                    summary = result.review.summary,
                    findings = result.review.findings.map {
                        FindingDto(
                            severity = it.severity.name,
                            category = it.category,
                            instancePath = it.instancePath,
                            evidence = it.evidence,
                            recommendation = it.recommendation,
                            requiresHumanDecision = it.requiresHumanDecision,
                        )
                    },
                    model = result.proposal.modelId ?: "unknown",
                ),
            ).build()
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "catalog review was rejected")
        } catch (e: CatalogReviewService.ModelUnavailableException) {
            log.warn("catalog review unavailable: no eligible self-hosted model", e)
            Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(mapOf("error" to "catalog review model is unavailable"))
                .build()
        }
    }

    private fun validUuid(value: String?): String? = value?.trim()?.takeIf {
        runCatching { UUID.fromString(it) }.isSuccess
    }

    private fun badRequest(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to message)).build()

    private companion object {
        val log: Logger = Logger.getLogger(CatalogReviewResource::class.java)
    }
}
