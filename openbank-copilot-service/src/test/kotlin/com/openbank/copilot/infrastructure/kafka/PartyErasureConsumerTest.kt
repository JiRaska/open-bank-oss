// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.infrastructure.observability.CopilotMetricsAdapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Routing/robustness cover for the PARTY_ERASED consumer (#3870). That the delete actually removes
 * the ROW is proven against a real database in `ConversationErasureIT` — a mocked store here could
 * not tell deletion from the read-side `expires_at` filter, which is the defect being fixed.
 */
class PartyErasureConsumerTest {

    private val conversationStore = mockk<ConversationStore>()
    private val metrics = mockk<CopilotMetricsAdapter>(relaxed = true)
    private lateinit var consumer: PartyErasureConsumer

    @BeforeEach
    fun setUp() {
        consumer = PartyErasureConsumer().also {
            it.conversationStore = conversationStore
            it.objectMapper = ObjectMapper()
            it.metrics = metrics
        }
    }

    @Test
    fun `a delete that removed rows is counted as erased`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { conversationStore.deleteForParty(partyId.toString()) } returns 2L

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        verify(exactly = 1) { metrics.recordPartyErasure(CopilotMetricsAdapter.OUTCOME_ERASED) }
        verify(exactly = 0) { metrics.recordPartyErasure(CopilotMetricsAdapter.OUTCOME_NO_MATCH) }
    }

    /**
     * The #4175 half that a log line could not express: an erasure that matched nothing is a
     * DIFFERENT outcome from one that removed rows, and must be readable by an alert or a query
     * rather than by grepping INFO for the digit zero.
     *
     * RED against `origin/main`: the consumer had no metrics port at all, so both cases produced
     * the same `log.infof` and nothing downstream could tell them apart.
     */
    @Test
    fun `a delete that matched nothing is counted as no_match, not as a successful erasure`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { conversationStore.deleteForParty(partyId.toString()) } returns 0L

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        verify(exactly = 1) { metrics.recordPartyErasure(CopilotMetricsAdapter.OUTCOME_NO_MATCH) }
        verify(exactly = 0) { metrics.recordPartyErasure(CopilotMetricsAdapter.OUTCOME_ERASED) }
    }

    @Test
    fun `a store failure counts neither outcome`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { conversationStore.deleteForParty(partyId.toString()) } throws IllegalStateException("db down")

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        verify(exactly = 0) { metrics.recordPartyErasure(any()) }
    }

    @Test
    fun `PARTY_ERASED erases the party's conversation history`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { conversationStore.deleteForParty(partyId.toString()) } returns 3L

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { conversationStore.deleteForParty(partyId.toString()) }
    }

    @Test
    fun `other party events are ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"${UUID.randomUUID()}"}""")
        consumer.consume("""{"eventType":"PARTY_UPDATED","partyId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { conversationStore.deleteForParty(any()) }
    }

    @Test
    fun `a malformed payload is acked without throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"PARTY_ERASED"}""")
        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"not-a-uuid"}""")

        coVerify(exactly = 0) { conversationStore.deleteForParty(any()) }
    }

    @Test
    fun `a store failure is swallowed so one bad message cannot wedge the consumer group`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { conversationStore.deleteForParty(partyId.toString()) } throws IllegalStateException("db down")

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { conversationStore.deleteForParty(partyId.toString()) }
    }
}
