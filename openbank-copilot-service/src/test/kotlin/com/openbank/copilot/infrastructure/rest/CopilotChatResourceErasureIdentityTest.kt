// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.application.port.`in`.CopilotChatUseCase
import com.openbank.copilot.domain.ChatOutcome
import com.openbank.copilot.domain.ChatReply
import com.openbank.copilot.domain.ChatTurn
import com.openbank.copilot.infrastructure.observability.CopilotMetricsAdapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.UUID

/**
 * Cover for `CopilotChatResource.erasureIdentity()` — the GDPR Art. 17 identity recorded on every
 * conversation row (#4175). Before this test the claim extraction had **zero** coverage at any
 * layer: no test anywhere constructed a JWT carrying `party_id`, so the code could silently degrade
 * to its fallback (or read the wrong claim name) with every existing test still green.
 *
 * Unit level deliberately, on the model of `ActionConfirmResourceTest`: the identity resolution is
 * pure JWT reading, so a mocked [CopilotChatUseCase] can capture the value the resource derived
 * without a Vert.x context — a reactive-Panache store cannot be driven from a bare test thread at
 * all. `PartyErasureIdentityIT` remains the proof that the recorded value then deletes the row.
 *
 * RED against `origin/main` (`2848bc566`): `absent` and `not a JsonWebToken` both asserted null and
 * both got the OIDC `sub` back, because `erasureIdentity()` fell back to `customerSubject()`. That
 * fallback bought no reach — `deleteForParty` already matches `customerId` — it only made a
 * fabricated party id indistinguishable from a real one.
 */
class CopilotChatResourceErasureIdentityTest {

    private val chat: CopilotChatUseCase = mockk()
    private val identity: SecurityIdentity = mockk()
    private val metrics: CopilotMetricsAdapter = mockk(relaxed = true)

    private lateinit var resource: CopilotChatResource

    private val sub = UUID.randomUUID().toString()
    private val partyId = UUID.randomUUID().toString()
    private val reply = ChatReply(conversationId = "new", reply = "ok")

    @BeforeEach
    fun setUp() {
        resource = CopilotChatResource()
        resource.chat = chat
        resource.identity = identity
        resource.metrics = metrics
    }

    /** Stub a bearer principal whose `party_id` claim is [claim] (null = the claim is absent). */
    private fun stubJwt(claim: String?) {
        val jwt = mockk<JsonWebToken>()
        every { jwt.subject } returns sub
        every { jwt.getClaim<String>("party_id") } returns claim
        every { identity.principal } returns jwt
    }

    /** Drive the real endpoint method and return the erasure identity it handed the use case. */
    private fun capturedPartyId(): String? {
        val captured = slot<String?>()
        coEvery { chat.handle(any(), any(), captureNullable(captured)) } returns ChatOutcome.Replied(reply)

        resource.chat(CopilotChatResource.ChatRequest(message = "hi"))

        return captured.captured
    }

    @Test
    fun `a token carrying party_id records that claim as the erasure identity`() {
        stubJwt(partyId)

        // Exact value, never isNotNull(): the defect this covers returns a DIFFERENT non-null
        // UUID (the sub), which every non-nullity assertion passes against.
        assertThat(capturedPartyId()).isEqualTo(partyId)
        assertThat(capturedPartyId()).isNotEqualTo(sub)
        verify { metrics.recordErasureIdentity(CopilotMetricsAdapter.SOURCE_CLAIM) }
    }

    @Test
    fun `a token with no party_id claim records NO erasure identity, never the sub`() {
        stubJwt(claim = null)

        assertThat(capturedPartyId()).isNull()
        verify { metrics.recordErasureIdentity(CopilotMetricsAdapter.SOURCE_ABSENT) }
    }

    @Test
    fun `a blank party_id claim is treated as absent, not stored verbatim`() {
        stubJwt("   ")

        assertThat(capturedPartyId()).isNull()
        verify { metrics.recordErasureIdentity(CopilotMetricsAdapter.SOURCE_ABSENT) }
    }

    @Test
    fun `a non-JWT principal yields no erasure identity`() {
        val principal = mockk<Principal>()
        every { principal.name } returns sub
        every { identity.principal } returns principal

        assertThat(capturedPartyId()).isNull()
        verify { metrics.recordErasureIdentity(CopilotMetricsAdapter.SOURCE_ABSENT) }
    }

    @Test
    fun `the chat still proceeds when no party id can be resolved`() {
        stubJwt(claim = null)
        val turn = slot<ChatTurn>()
        coEvery { chat.handle(capture(turn), any(), any()) } returns ChatOutcome.Replied(reply)

        val response = resource.chat(CopilotChatResource.ChatRequest(message = "hi"))

        // The decision recorded in erasureIdentity()'s KDoc: refusing the turn would not erase one
        // extra row, so an unresolvable party id must never cost the customer their assistant.
        assertThat(response.status).isEqualTo(HTTP_OK)
        assertThat(turn.captured.message).isEqualTo("hi")
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
