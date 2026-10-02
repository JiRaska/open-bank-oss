// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.application.CatalogReviewService
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The human-triggered catalog review endpoint. Its whole job beyond delegation is input
 * validation and mapping the two failure modes onto distinguishable statuses: a malformed
 * request (400) versus no eligible self-hosted model (503), which an operator must be able to
 * tell apart from a bad request.
 */
class CatalogReviewResourceTest {

    private val reviews = mockk<CatalogReviewService>()
    private val resource = CatalogReviewResource().also { it.reviews = reviews }

    private val offeringId = UUID.randomUUID().toString()
    private val revisionId = UUID.randomUUID().toString()

    private fun result(): CatalogReviewService.Result = CatalogReviewService.Result(
        proposal = AgentProposal(
            id = UUID.randomUUID(),
            title = "catalog review",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "catalog-reviewer",
            proposedAt = Instant.EPOCH,
            state = ProposalState.PROPOSED,
            decidedBy = null,
            decidedAt = null,
            decisionReason = null,
            modelId = "self-hosted-70b",
            correlationId = null,
        ),
        review = CatalogReviewService.Review(
            summary = "two issues",
            findings = listOf(
                CatalogReviewService.Finding(
                    severity = CatalogReviewService.Severity.HIGH,
                    category = "pricing",
                    instancePath = "/fees/0",
                    evidence = "fee exceeds cap",
                    recommendation = "lower it",
                    requiresHumanDecision = true,
                ),
            ),
        ),
        contextHash = "hash-1",
    )

    @Test
    fun `a valid request is 201 with the proposal, context hash and mapped findings`() {
        coEvery { reviews.review(offeringId, revisionId, null) } returns result()

        val response = resource.review(CatalogReviewResource.ReviewRequest(offeringId, revisionId))

        assertThat(response.status).isEqualTo(201)
        val dto = response.entity as CatalogReviewResource.ResponseDto
        assertThat(dto.state).isEqualTo("PROPOSED")
        assertThat(dto.contextHash).isEqualTo("hash-1")
        assertThat(dto.summary).isEqualTo("two issues")
        assertThat(dto.model).isEqualTo("self-hosted-70b")
        assertThat(dto.findings).singleElement().satisfies({
            assertThat(it.severity).isEqualTo("HIGH")
            assertThat(it.instancePath).isEqualTo("/fees/0")
            assertThat(it.requiresHumanDecision).isTrue()
        })
    }

    @Test
    fun `ids are trimmed before use, so surrounding whitespace is not a 400`() {
        coEvery { reviews.review(offeringId, revisionId, "m") } returns result()

        val response = resource.review(
            CatalogReviewResource.ReviewRequest(" $offeringId ", "$revisionId\t", "m"),
        )

        assertThat(response.status).isEqualTo(201)
        coVerify { reviews.review(offeringId, revisionId, "m") }
    }

    @Test
    fun `a null or non-UUID offeringId is a 400 and no review is started`() {
        listOf(null, "", "off-1").forEach { bad ->
            val response = resource.review(CatalogReviewResource.ReviewRequest(bad, revisionId))
            assertThat(response.status).describedAs("offeringId=$bad").isEqualTo(400)
            assertThat(response.entity.toString()).contains("offeringId must be a UUID")
        }
        coVerify(exactly = 0) { reviews.review(any(), any(), any()) }
    }

    @Test
    fun `a bad revisionId is reported against revisionId, not offeringId`() {
        val response = resource.review(CatalogReviewResource.ReviewRequest(offeringId, "rev-1"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity.toString()).contains("revisionId must be a UUID")
    }

    @Test
    fun `a rejected review surfaces as 400 with the service's own message`() {
        coEvery { reviews.review(offeringId, revisionId, null) } throws
            IllegalArgumentException("revision is not reviewable in state DRAFT")

        val response = resource.review(CatalogReviewResource.ReviewRequest(offeringId, revisionId))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity.toString()).contains("not reviewable")
    }

    @Test
    fun `no eligible self-hosted model is 503, distinguishable from a bad request`() {
        coEvery { reviews.review(offeringId, revisionId, null) } throws
            CatalogReviewService.ModelUnavailableException()

        val response = resource.review(CatalogReviewResource.ReviewRequest(offeringId, revisionId))

        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity).isEqualTo(mapOf("error" to "catalog review model is unavailable"))
    }
}
