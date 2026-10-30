// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/AGPL-3.0-only.txt for details.

package com.openbank.agent.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.application.port.out.CatalogRevisionReadPort
import com.openbank.agent.application.port.out.CatalogRevisionSnapshot
import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.Sensitivity
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CatalogReviewServiceTest {

    private val mapper = jacksonObjectMapper()
    private val gatewayEndpoint = "http://model-gateway.svc:4000/v1"
    private val snapshot = CatalogRevisionSnapshot(
        offeringId = "00000000-0000-0000-0000-000000000001",
        revisionId = "00000000-0000-0000-0000-000000000002",
        state = "DRAFT",
        schemaRef = "org.example.insurance.term-life@1",
        contentHash = null,
        document = """{"id":"00000000-0000-0000-0000-000000000002","state":"DRAFT","attributes":{"termYears":10}}""",
    )

    private fun gateway(vararg models: ModelDescriptor, default: String = models.first().id): ModelGateway = mockk {
        every { availableModels() } returns models.toList()
        every { defaultModelId() } returns default
    }

    @Suppress("LongMethod")
    @Test
    fun `review grounds an exact draft with no tools and persists structured provenance`(): Unit = runBlocking {
        val reader = mockk<CatalogRevisionReadPort> {
            every { get(snapshot.offeringId, snapshot.revisionId) } returns
                snapshot
        }
        val chat = mockk<AgentChatService>()
        coEvery {
            chat.run(any(), any(), any(), any(), any(), any(), any(), any())
        } returns AgentChatService.ChatOutcome(
            reply = """
                {"summary":"Term is complete.","findings":[
                  {"severity":"WARNING","category":"disclosure","instancePath":"/attributes/termYears",
                   "evidence":"No exclusion reference is present.","recommendation":"Ask a human checker to confirm the disclosure.",
                   "requiresHumanDecision":true}
                ]}
            """.trimIndent(),
            model = "local-reviewer",
            toolCalls = emptyList(),
            isProposal = true,
        )
        val proposals = mockk<CreateProposalUseCase>()
        every { proposals.create(any(), any(), any(), any(), any(), any(), any()) } answers {
            AgentProposal(
                id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
                title = firstArg(),
                rationale = secondArg(),
                suggestedAction = thirdArg(),
                proposedBy = arg(3),
                proposedAt = Instant.EPOCH,
                state = ProposalState.PROPOSED,
                decidedBy = null,
                decidedAt = null,
                decisionReason = null,
                modelId = arg(4),
                correlationId = arg(5),
                metadata = arg(6),
            )
        }

        val result = CatalogReviewService(
            reader,
            chat,
            proposals,
            mapper,
            gateway(
                ModelDescriptor("local-reviewer", "openai-compat", "http://local/v1", Sensitivity.SELF_HOSTED),
            ),
        )
            .review(snapshot.offeringId, snapshot.revisionId, "local-reviewer")

        assertThat(result.proposal.state).isEqualTo(ProposalState.PROPOSED)
        val finding = result.review.findings.single()
        assertThat(finding.instancePath).isEqualTo("/attributes/termYears")
        assertThat(finding.requiresHumanDecision).isTrue()
        assertThat(result.proposal.metadata)
            .containsEntry("kind", "catalog_review")
            .containsEntry("catalog_revision_id", snapshot.revisionId)
            .containsKey("context_hash")
            .containsKey("review_json")
        coVerify(exactly = 1) {
            chat.run(
                any(),
                any(),
                match { it.single().content.contains("SNAPSHOT_SHA256") },
                eq("local-reviewer"),
                eq("catalog_review"),
                eq(emptySet()),
                eq(true),
                eq(true),
            )
        }
    }

    @Test
    fun `published revision never reaches model or proposal queue`(): Unit = runBlocking {
        val published = snapshot.copy(state = "PUBLISHED")
        val reader = mockk<CatalogRevisionReadPort> { every { get(any(), any()) } returns published }
        val chat = mockk<AgentChatService>()
        val proposals = mockk<CreateProposalUseCase>()

        assertThatThrownBy {
            runBlocking {
                CatalogReviewService(
                    reader,
                    chat,
                    proposals,
                    mapper,
                    gateway(ModelDescriptor("private", "openai-compat", "http://local/v1", Sensitivity.SELF_HOSTED)),
                ).review("offering", "revision", null)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("only a DRAFT revision can be reviewed")
        coVerify(exactly = 0) { chat.run(any(), any(), any(), any(), any(), any(), any(), any()) }
        io.mockk.verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown output fields are rejected without a proposal`(): Unit = runBlocking {
        val reader = mockk<CatalogRevisionReadPort> { every { get(any(), any()) } returns snapshot }
        val chat = mockk<AgentChatService>()
        coEvery { chat.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AgentChatService.ChatOutcome(
                reply = """{"summary":"x","findings":[],"patch":"publish"}""",
                model = "local-reviewer",
                toolCalls = emptyList(),
                isProposal = true,
            )
        val proposals = mockk<CreateProposalUseCase>()

        assertThatThrownBy {
            runBlocking {
                CatalogReviewService(
                    reader,
                    chat,
                    proposals,
                    mapper,
                    gateway(ModelDescriptor("private", "openai-compat", "http://local/v1", Sensitivity.SELF_HOSTED)),
                ).review(snapshot.offeringId, snapshot.revisionId, null)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown fields")
        io.mockk.verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unavailable model creates no proposal`(): Unit = runBlocking {
        val reader = mockk<CatalogRevisionReadPort> { every { get(any(), any()) } returns snapshot }
        val chat = mockk<AgentChatService>()
        coEvery { chat.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AgentChatService.ChatOutcome(
                reply = "The model backend is temporarily unavailable — please try again in a moment.",
                model = "local-reviewer",
                toolCalls = emptyList(),
                unavailable = true,
            )
        val proposals = mockk<CreateProposalUseCase>()

        assertThatThrownBy {
            runBlocking {
                CatalogReviewService(
                    reader,
                    chat,
                    proposals,
                    mapper,
                    gateway(ModelDescriptor("private", "openai-compat", "http://local/v1", Sensitivity.SELF_HOSTED)),
                ).review(snapshot.offeringId, snapshot.revisionId, null)
            }
        }.isInstanceOf(CatalogReviewService.ModelUnavailableException::class.java)
        io.mockk.verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sandbox opt-in permits only hosted registry entries at the in-cluster LiteLLM endpoint`() {
        val service = CatalogReviewService(
            mockk(),
            mockk(),
            mockk(),
            mapper,
            gateway(
                ModelDescriptor("other-internal", "openai-compat", "http://other.svc:4000/v1", Sensitivity.HOSTED),
                ModelDescriptor("approved", "openai-compat", gatewayEndpoint, Sensitivity.HOSTED),
                ModelDescriptor("direct", "openai-compat", "https://provider.example/v1", Sensitivity.HOSTED),
                ModelDescriptor("mock", "mock", null, Sensitivity.HOSTED),
                ModelDescriptor("private", "openai-compat", "http://local/v1", Sensitivity.SELF_HOSTED),
                default = "approved",
            ),
        ).also {
            it.allowHostedModels = true
        }

        val capability = service.capability()
        assertThat(capability.available).isTrue()
        assertThat(capability.mode).isEqualTo("sandbox-hosted")
        assertThat(capability.defaultModel).isEqualTo("approved")
        assertThat(capability.models.map { it.id }).containsExactly("approved")
    }

    @Test
    fun `hosted opt-in fails closed when configured default is not an approved route`() {
        val service = CatalogReviewService(
            mockk(),
            mockk(),
            mockk(),
            mapper,
            gateway(
                ModelDescriptor("approved", "openai-compat", gatewayEndpoint, Sensitivity.HOSTED),
                default = "mock-echo",
            ),
        ).also {
            it.allowHostedModels = true
        }

        val capability = service.capability()
        assertThat(capability.available).isFalse()
        assertThat(capability.defaultModel).isNull()
        assertThat(capability.models).isEmpty()
    }

    @Test
    fun `without sandbox opt-in capability mirrors existing self-hosted sensitive routing`() {
        val service = CatalogReviewService(
            mockk(),
            mockk(),
            mockk(),
            mapper,
            gateway(
                ModelDescriptor("hosted-default", "openai-compat", gatewayEndpoint, Sensitivity.HOSTED),
                ModelDescriptor("private", "openai-compat", "http://local/v1", Sensitivity.SELF_HOSTED),
                default = "hosted-default",
            ),
        )

        val capability = service.capability()
        assertThat(capability.available).isTrue()
        assertThat(capability.mode).isEqualTo("self-hosted")
        assertThat(capability.defaultModel).isEqualTo("private")
        assertThat(capability.models.map { it.id }).containsExactly("private")
    }

    @Test
    fun `explicit approved sandbox model is sent without changing global sensitive routing`(): Unit = runBlocking {
        val reader = mockk<CatalogRevisionReadPort> {
            every { get(snapshot.offeringId, snapshot.revisionId) } returns
                snapshot
        }
        val chat = mockk<AgentChatService>()
        coEvery {
            chat.run(any(), any(), any(), any(), any(), any(), false, true)
        } returns AgentChatService.ChatOutcome(
            reply = """{"summary":"Checked.","findings":[]}""",
            model = "approved",
            toolCalls = emptyList(),
            isProposal = true,
        )
        val proposals = mockk<CreateProposalUseCase>()
        every { proposals.create(any(), any(), any(), any(), any(), any(), any()) } answers {
            AgentProposal(
                id = UUID.randomUUID(), title = firstArg(), rationale = secondArg(), suggestedAction = thirdArg(),
                proposedBy = arg(3), proposedAt = Instant.EPOCH, state = ProposalState.PROPOSED,
                decidedBy = null, decidedAt = null, decisionReason = null, modelId = arg(4),
                correlationId = arg(5), metadata = arg(6),
            )
        }
        val service = CatalogReviewService(
            reader,
            chat,
            proposals,
            mapper,
            gateway(ModelDescriptor("approved", "openai-compat", gatewayEndpoint, Sensitivity.HOSTED)),
        ).also {
            it.allowHostedModels = true
        }

        val result = service.review(snapshot.offeringId, snapshot.revisionId, "approved")

        assertThat(result.proposal.state).isEqualTo(ProposalState.PROPOSED)
        coVerify(exactly = 1) {
            chat.run(any(), any(), any(), eq("approved"), eq("catalog_review"), eq(emptySet()), eq(false), eq(true))
        }
    }

    @Test
    fun `sandbox review rejects a caller supplied model outside the capability allow-list`() {
        runBlocking {
            val service = CatalogReviewService(
                mockk(),
                mockk(),
                mockk(),
                mapper,
                gateway(ModelDescriptor("approved", "openai-compat", gatewayEndpoint, Sensitivity.HOSTED)),
            ).also {
                it.allowHostedModels = true
            }

            assertThatThrownBy {
                runBlocking { service.review(snapshot.offeringId, snapshot.revisionId, "https://other/v1") }
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("model is not eligible for catalog review")
        }
    }
}
