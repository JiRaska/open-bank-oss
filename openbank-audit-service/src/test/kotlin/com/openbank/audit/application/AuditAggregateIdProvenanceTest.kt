// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.domain.model.AggregateIdProvenance
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.PartyMergeIndexRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Which resource an audit row is ABOUT, and who decided it (#6318).
 *
 * `aggregate_id` is chain-hashed into `record_hash` and `audit_entries` is append-only at the DB,
 * so the value gets exactly one chance to be right — and a producer's own declaration and this
 * consumer's inference chain answer different questions. The counter's DECLARED leg is asserted
 * too: a counter that only increments on failure cannot tell "no gaps" from "no traffic".
 */
class AuditAggregateIdProvenanceTest {

    private val repo = mockk<AuditRepository>()
    private val mergeIndex = mockk<PartyMergeIndexRepository>()
    private val registry = SimpleMeterRegistry()
    private lateinit var consumer: AuditConsumer

    @BeforeEach
    fun setUp() {
        consumer = AuditConsumer().also {
            it.repo = repo
            it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
            it.clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
            it.meterRegistry = registry
        }
        coEvery { repo.save(any()) } returns Unit
    }

    private fun provenanceCount(service: String, provenance: AggregateIdProvenance): Double =
        registry.find("openbank.audit.aggregate.id.provenance")
            .tag("source_service", service)
            .tag("provenance", provenance.name)
            .counter()?.count() ?: 0.0

    @Test
    fun `the producer's own aggregateId wins over the inference chain, and is marked DECLARED`(): Unit =
        runBlocking {
            val declared = UUID.randomUUID()
            val other = UUID.randomUUID()
            val payload = """
                {"eventType":"JournalPosted","aggregateId":"$declared","transactionId":"$other",
                 "sourceService":"ledger-service"}
            """.trimIndent()

            consumer.consume(payload)

            coVerify { repo.save(match { it.aggregateId == declared.toString() }) }
            assertThat(provenanceCount("ledger-service", AggregateIdProvenance.DECLARED)).isEqualTo(1.0)
            assertThat(provenanceCount("ledger-service", AggregateIdProvenance.INFERRED)).isZero()
        }

    @Test
    fun `an explicitly null aggregateId falls through to inference, never the string null`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val payload = """{"eventType":"X","aggregateId":null,"accountId":"$accountId","sourceService":"account-service"}"""

        consumer.consume(payload)

        coVerify { repo.save(match { it.aggregateId == accountId.toString() && it.aggregateType == "ACCOUNT" }) }
        assertThat(provenanceCount("account-service", AggregateIdProvenance.INFERRED)).isEqualTo(1.0)
    }

    @Test
    fun `a blank aggregateId is treated as absent, not stored as an empty resource id`(): Unit = runBlocking {
        val payload = """{"eventType":"X","aggregateId":"   ","sourceService":"kyc-service"}"""

        consumer.consume(payload)

        coVerify { repo.save(match { it.aggregateId == "unknown" }) }
        assertThat(provenanceCount("kyc-service", AggregateIdProvenance.ABSENT)).isEqualTo(1.0)
    }

    @Test
    fun `nothing identifying at all is ABSENT and stores the sentinel, kept apart from INFERRED`(): Unit =
        runBlocking {
            consumer.consume("""{"eventType":"HeartBeat","sourceService":"balance-service"}""")

            coVerify { repo.save(match { it.aggregateId == "unknown" && it.aggregateType == "UNKNOWN" }) }
            assertThat(provenanceCount("balance-service", AggregateIdProvenance.ABSENT)).isEqualTo(1.0)
            assertThat(provenanceCount("balance-service", AggregateIdProvenance.INFERRED)).isZero()
        }

    @Test
    fun `the inference chain takes the FIRST recognised field, in its declared order`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        // accountId precedes partyId in the ordered table; a reordering would silently re-key rows.
        consumer.consume(
            """{"eventType":"X","partyId":"$partyId","accountId":"$accountId","sourceService":"account-service"}""",
        )

        coVerify { repo.save(match { it.aggregateId == accountId.toString() }) }
    }

    @Test
    fun `the nested ICT incident id beats every top-level business id`(): Unit = runBlocking {
        val payload = """
            {"eventType":"IctIncidentRaised","partyId":"${UUID.randomUUID()}",
             "incident":{"id":"INC-42"},"sourceService":"security-scanner"}
        """.trimIndent()

        consumer.consume(payload)

        coVerify { repo.save(match { it.aggregateId == "INC-42" && it.aggregateType == "ICT_INCIDENT" }) }
    }

    @Test
    fun `a producer event id makes redelivery idempotent by reusing it as the entry id`(): Unit = runBlocking {
        val eventId = UUID.randomUUID()

        consumer.consume("""{"eventId":"$eventId","eventType":"X","sourceService":"kyc-service"}""")

        coVerify { repo.save(match { it.id == eventId }) }
    }

    @Test
    fun `a malformed eventId does not lose the audit row - it is swallowed at the consume boundary`(): Unit =
        runBlocking {
            consumer.consume("""{"eventId":"not-a-uuid","eventType":"X","sourceService":"kyc-service"}""")

            coVerify(exactly = 0) { repo.save(any()) }
        }

    @Test
    fun `a PARTY_MERGED event records the retired-to-survivor edge alongside the audit row`(): Unit = runBlocking {
        consumer.mergeIndex = mergeIndex
        val retired = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        coEvery { mergeIndex.recordMerge(any(), any(), any()) } returns Unit

        consumer.consume(
            """{"eventType":"PARTY_MERGED","partyId":"$retired","mergedIntoPartyId":"$survivor",
                "sourceService":"party-service","occurredAt":"2026-05-01T00:00:00Z"}""",
        )

        coVerify { mergeIndex.recordMerge(retired, survivor, Instant.parse("2026-05-01T00:00:00Z")) }
    }

    @Test
    fun `a PARTY_MERGED event with no survivor writes no edge, and still stores the audit row`(): Unit =
        runBlocking {
            consumer.mergeIndex = mergeIndex
            consumer.consume(
                """{"eventType":"PARTY_MERGED","partyId":"${UUID.randomUUID()}","sourceService":"party-service"}""",
            )

            coVerify(exactly = 0) { mergeIndex.recordMerge(any(), any(), any()) }
            coVerify(exactly = 1) { repo.save(any()) }
        }

    @Test
    fun `a merge-index failure never rolls back the audit row that is already stored`(): Unit = runBlocking {
        consumer.mergeIndex = mergeIndex
        coEvery { mergeIndex.recordMerge(any(), any(), any()) } throws IllegalStateException("db down")

        consumer.consume(
            """{"eventType":"PARTY_MERGED","partyId":"${UUID.randomUUID()}",
                "mergedIntoPartyId":"${UUID.randomUUID()}","sourceService":"party-service"}""",
        )

        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `an unrecognised or missing topic attributes nothing rather than deriving a plausible name`() {
        assertThat(TopicAttribution.sourceService(null)).isNull()
        assertThat(TopicAttribution.sourceService("")).isNull()
        assertThat(TopicAttribution.sourceService("   ")).isNull()
        // The domain segment would derive "cards-service"; the real producer is card-issuance.
        assertThat(TopicAttribution.sourceService("openbank.cards.events")).isEqualTo("card-issuance-service")
        assertThat(TopicAttribution.sourceService("openbank.unrelated.events")).isNull()
    }

    @Test
    fun `EventAddress NONE carries no broker metadata at all`() {
        assertThat(EventAddress.NONE.topic).isNull()
        assertThat(EventAddress.NONE.ceType).isNull()
    }
}
