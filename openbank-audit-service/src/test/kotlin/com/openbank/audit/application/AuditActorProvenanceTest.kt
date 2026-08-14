// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.domain.model.ActorProvenance
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The actor half of #3994: `openbank.audit.actor.missing` (#3994).
 *
 * 75% of the live audit trail names no actor, and until this counter existed that was answerable
 * only by hand-running a `GROUP BY` against the audit database — there was no series to alert on,
 * degrade a dashboard, or notice a producer regressing.
 *
 * **Every assertion here checks a VALUE — the exact actor id, the exact provenance, the exact tag
 * set — never `isNotNull()`.** That is the discipline the sibling [AuditAttributionTest] states
 * and the reason this whole class of defect survived: `actor_id` was NULL, not a sentinel, so
 * nothing was ever wrong enough to fail a non-null assertion. A test that asserts presence passes
 * against a wrong-but-present value; a test that asserts `"unknown"`-vs-a-real-name does not.
 */
class AuditActorProvenanceTest {

    private val repo = mockk<AuditRepository>()

    private val registry = SimpleMeterRegistry()

    private lateinit var consumer: AuditConsumer

    @BeforeEach
    fun setUp() {
        consumer = AuditConsumer().also {
            it.repo = repo
            it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
            it.clock = Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC)
            it.meterRegistry = registry
        }
    }

    @Test
    fun `each of the three actor spellings is read, and the id is the one the producer sent`(): Unit = runBlocking {
        // The VALUE, not merely a non-null column: the whole point is that the row names the RIGHT
        // person. `findByActorId` (ADR-0226) and the GDPR Art. 15 access log both key on this, so
        // an id that is present but wrong returns another data subject's operations.
        val spellings = mapOf(
            "requestedBy" to "op-1",
            "actorId" to "op-2",
            // transaction.initiated carries the customer identity here (ADR-0021).
            "initiatedByPartyId" to "op-3",
        )

        for ((field, id) in spellings) {
            val entry = capturingSave()
            consumer.consume("""{"accountId":"${UUID.randomUUID()}","$field":"$id"}""")
            assertThat(entry.captured.actorId).isEqualTo(id)
        }
    }

    @Test
    fun `sanctions reviewedBy is the actor of a manual four-eyes review`(): Unit = runBlocking {
        // RED before this change: the payload is the serialised SanctionsCheck aggregate, so
        // `reviewedBy` is a Kotlin property name with no string literal in the producer — invisible
        // to a grep, and unread by the consumer's three spellings. This is the four-eyes review
        // identity, the highest-value actor in the fleet, and it was landing as NULL.
        val entry = capturingSave()

        consumer.consume(
            """{"id":"${UUID.randomUUID()}","status":"CLEARED","reviewedBy":"analyst-14"}""",
            EventAddress(topic = "openbank.sanctions.screening.event"),
        )

        assertThat(entry.captured.actorId).isEqualTo("analyst-14")
        assertThat(counter("sanctions-service", ActorProvenance.DECLARED)).isEqualTo(1.0)
    }

    @Test
    fun `card changedBy is the actor of a status, limit or control change`(): Unit = runBlocking {
        // Three of the four card event types carry `changedBy` (only CardIssued omits it) — again
        // a serialised data class, so no literal to grep. RED before this change.
        val entry = capturingSave()

        consumer.consume(
            """{"cardId":"${UUID.randomUUID()}","newStatus":"BLOCKED","changedBy":"op-9"}""",
            EventAddress(topic = "openbank.cards.events"),
        )

        assertThat(entry.captured.actorId).isEqualTo("op-9")
    }

    @Test
    fun `lending actorKind classifies an actor whose id was already read`(): Unit = runBlocking {
        // The asymmetric case: lending's transition events emit `actorId` AND `actorKind`, so the
        // id was already caught while the type beside it was dropped. The row named the actor but
        // could not say whether it was a human or the automated policy engine — which is the whole
        // question a four-eyes or DORA reconstruction asks of a credit decision.
        val entry = capturingSave()

        consumer.consume(
            """{"loanId":"${UUID.randomUUID()}","actorId":"officer-3","actorKind":"HUMAN"}""",
            EventAddress(topic = "openbank.lending.events"),
        )

        assertThat(entry.captured.actorId).isEqualTo("officer-3")
        assertThat(entry.captured.actorType).isEqualTo("HUMAN")
    }

    @Test
    fun `the three original spellings still win, so no attributed row is re-attributed`(): Unit = runBlocking {
        // The recoveries are appended, never inserted ahead of the existing chain. A change that
        // improves 1359 rows by silently moving the 425 that were already right is a regression.
        val entry = capturingSave()

        consumer.consume(
            """{"cardId":"${UUID.randomUUID()}","requestedBy":"op-1","reviewedBy":"x","changedBy":"y"}""",
            EventAddress(topic = "openbank.cards.events"),
        )

        assertThat(entry.captured.actorId).isEqualTo("op-1")
    }

    @Test
    fun `a declared actor is counted as DECLARED, tagged with its producing service`(): Unit = runBlocking {
        // DECLARED is counted too, not only the absences. A counter that increments only on
        // failure cannot tell "no gaps" from "no traffic" — a ratio needs its denominator.
        coEvery { repo.save(any()) } returns Unit

        consumer.consume(
            """{"transactionId":"${UUID.randomUUID()}","initiatedByPartyId":"party-77"}""",
            EventAddress(topic = "openbank.transactions.transaction.initiated"),
        )

        assertThat(counter("transaction-service", ActorProvenance.DECLARED)).isEqualTo(1.0)
        assertThat(counter("transaction-service", ActorProvenance.ABSENT)).isEqualTo(0.0)
    }

    @Test
    fun `an asserted non-human actor is SYSTEM, not folded in with a silent omission`(): Unit = runBlocking {
        // The distinction the metric exists to draw. A scheduled balance update genuinely has no
        // human actor and its producer says so; kyc-service simply sends nothing. Both leave
        // `actor_id` NULL, so a naive "count the nulls" gauge would report them as one number and
        // the producer sweep would have no idea which rows it still has to fix.
        coEvery { repo.save(any()) } returns Unit

        consumer.consume(
            """{"accountId":"${UUID.randomUUID()}","actorType":"SYSTEM"}""",
            EventAddress(topic = "openbank.balance.events"),
        )

        assertThat(counter("balance-service", ActorProvenance.SYSTEM)).isEqualTo(1.0)
        assertThat(counter("balance-service", ActorProvenance.ABSENT)).isEqualTo(0.0)
    }

    @Test
    fun `a producer that says nothing about the actor is ABSENT, and the row still stores`(): Unit = runBlocking {
        // 1359 live rows are exactly this: PARTY_CREATED, KYC_CASE_APPROVED, AccountStatusChanged,
        // ConsentRevoked — decisions with a decider, recorded naming nobody. Measured across every
        // one of those payloads' keys, no actor field is present under ANY spelling, so there is
        // nothing for this consumer to recover and nothing it may invent: `actor_id` is
        // chain-hashed into `record_hash`, and a fabricated actor is worse than an honest gap.
        val entry = capturingSave()

        consumer.consume(
            """{"partyId":"${UUID.randomUUID()}","eventType":"PARTY_CREATED"}""",
            EventAddress(topic = "openbank.party.events"),
        )

        assertThat(entry.captured.actorId).isNull()
        assertThat(entry.captured.actorType).isNull()
        assertThat(counter("party-service", ActorProvenance.ABSENT)).isEqualTo(1.0)
    }

    @Test
    fun `an explicit JSON null actor is ABSENT, never the four-character string null`(): Unit = runBlocking {
        // Jackson's asText() on a NullNode returns the STRING "null". Seven live rows carry
        // `actor_id = 'null'` from before that was fixed — all money-path TransactionInitiated —
        // and that is strictly worse than the NULL it replaced: a NULL actor reads as a known gap
        // and gets counted here, whereas "null" reads as an attributed row and is invisible to
        // this counter. This asserts BOTH halves — the stored value and the classification — so a
        // regression in `textOrNull` cannot pass by leaving the counter alone.
        val entry = capturingSave()

        consumer.consume(
            """{"transactionId":"${UUID.randomUUID()}","actorId":null,"actorType":null}""",
            EventAddress(topic = "openbank.transactions.transaction.initiated"),
        )

        assertThat(entry.captured.actorId).isNull()
        assertThat(counter("transaction-service", ActorProvenance.ABSENT)).isEqualTo(1.0)
        assertThat(counter("transaction-service", ActorProvenance.DECLARED)).isEqualTo(0.0)
    }

    @Test
    fun `an unattributed producer still yields a usable series, tagged unknown`(): Unit = runBlocking {
        // Belt and braces with TopicAttribution: if a topic ever falls out of the map, the actor
        // gap must still be countable rather than silently disappearing from the dashboard.
        coEvery { repo.save(any()) } returns Unit

        consumer.consume("""{"accountId":"${UUID.randomUUID()}"}""")

        assertThat(counter("unknown", ActorProvenance.ABSENT)).isEqualTo(1.0)
    }

    @Test
    fun `the classifier answers about the stored row, for every combination`() {
        // Reads the resolved entry fields rather than re-parsing the payload: a second pass over
        // the JSON can disagree with the row it is meant to describe, which is precisely how
        // inferAggregateId and inferAggregateType came to contradict each other on a JSON null.
        assertThat(actorProvenance("op-1", "CUSTOMER")).isEqualTo(ActorProvenance.DECLARED)
        assertThat(actorProvenance("op-1", null)).isEqualTo(ActorProvenance.DECLARED)
        assertThat(actorProvenance(null, "SYSTEM")).isEqualTo(ActorProvenance.SYSTEM)
        assertThat(actorProvenance(null, null)).isEqualTo(ActorProvenance.ABSENT)
    }

    private fun counter(sourceService: String, provenance: ActorProvenance): Double = registry.counter(
        "openbank.audit.actor.missing",
        "source_service",
        sourceService,
        "provenance",
        provenance.name,
    ).count()

    private fun capturingSave() = slot<AuditEntry>().also {
        coEvery { repo.save(capture(it)) } returns Unit
    }
}
