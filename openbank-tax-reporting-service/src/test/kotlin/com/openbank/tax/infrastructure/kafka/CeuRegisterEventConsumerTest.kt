// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.tax.application.port.out.CeuRegisterRepository
import com.openbank.tax.application.usecase.CeuRegisterService
import com.openbank.tax.domain.ceu.CeuRole
import com.openbank.tax.domain.ceu.RegisterFact
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class CeuRegisterEventConsumerTest {
    /** In-memory repository: the consumer's real collaborator, not a mock that accepts anything. */
    private class FakeRepository : CeuRegisterRepository {
        val facts = linkedMapOf<UUID, RegisterFact>()

        override suspend fun append(fact: RegisterFact): Boolean = facts.putIfAbsent(fact.eventId, fact) == null

        override suspend fun startOf(relationId: UUID): RegisterFact.RelationStarted? =
            facts.values.filterIsInstance<RegisterFact.RelationStarted>().firstOrNull { it.relationId == relationId }

        override suspend fun factsUpTo(validOn: LocalDate, knownAt: Instant): List<RegisterFact> = facts.values.toList()

        val parties = linkedMapOf<UUID, Boolean>()

        override suspend fun classifyParty(partyId: UUID, synthetic: Boolean, recordedAt: Instant): Boolean =
            parties.putIfAbsent(partyId, synthetic) == null

        override suspend fun syntheticParties(knownAt: Instant): Set<UUID> = parties.filterValues { it }.keys
    }

    private val repo = FakeRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"), ZoneOffset.UTC)
    private val consumer =
        CeuRegisterEventConsumer(CeuRegisterService(repo), ObjectMapper(), SimpleMeterRegistry(), clock)
    private val account = UUID.randomUUID()
    private val holder = UUID.randomUUID()

    private fun record(type: String, payload: String, synthetic: String? = null, id: UUID = UUID.randomUUID()) =
        ConsumerRecord("t", 0, 0L, "k", payload).also { r ->
            r.headers().add(
                RecordHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE, type.toByteArray(StandardCharsets.UTF_8)),
            )
            r.headers().add(RecordHeader(OutboxKafkaHeaders.HEADER_EVENT_ID, id.toString().toByteArray()))
            synthetic?.let { r.headers().add(RecordHeader(OutboxKafkaHeaders.HEADER_SYNTHETIC, it.toByteArray())) }
        }

    // 23:30 UTC on 2 Nov is 00:30 on 3 Nov in Prague: CEÚ dates are Czech dates.
    private fun created(synthetic: String? = null, id: UUID = UUID.randomUUID()) = record(
        "AccountCreated",
        """{"aggregateId":"$account","accountNumber":"123456789/0800","currency":"CZK","partyId":"$holder",
           "occurredAt":"2026-11-02T23:30:00Z","eventType":"AccountCreated"}""",
        synthetic,
        id,
    )

    @Test
    fun `AccountCreated records the account and its holder on the Prague business date`(): Unit = runBlocking {
        consumer.consumeAccount(created())

        val opened = repo.facts.values.filterIsInstance<RegisterFact.AccountOpened>().single()
        val tie = repo.facts.values.filterIsInstance<RegisterFact.RelationStarted>().single()
        assertThat(opened.effectiveOn).isEqualTo(LocalDate.parse("2026-11-03"))
        assertThat(opened.synthetic).isFalse()
        assertThat(tie.role).isEqualTo(CeuRole.HOLDER)
        assertThat(tie.partyId).isEqualTo(holder)
    }

    @Test
    fun `a redelivered event is recorded once`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        consumer.consumeAccount(created(id = id))
        consumer.consumeAccount(created(id = id))
        assertThat(repo.facts).hasSize(2)
    }

    @Test
    fun `only an exact true taint header marks facts synthetic`(): Unit = runBlocking {
        consumer.consumeAccount(created(synthetic = "true"))
        consumer.consumeAccount(created(synthetic = "yes"))
        val flags = repo.facts.values.filterIsInstance<RegisterFact.AccountOpened>().map { it.synthetic }
        assertThat(flags).containsExactly(true, false)
    }

    @Test
    fun `an account disposer grant starts and ends a DISPOSER tie, other resources are ignored`(): Unit = runBlocking {
        val grant = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        fun activated(resourceType: String, g: UUID) = record(
            "DelegationActivated",
            """{"aggregateId":"$g","resourceType":"$resourceType","resourceId":"$account","grantorPartyId":"$holder",
               "granteePartyId":"$grantee","validFrom":"2026-11-02T10:00:00+01:00","occurredAt":"2026-11-02T09:00:00Z"}""",
        )
        consumer.consumeDelegation(activated("CARD", UUID.randomUUID()))
        consumer.consumeDelegation(activated("ACCOUNT", grant))
        consumer.consumeDelegation(
            record("DelegationRevoked", """{"aggregateId":"$grant","occurredAt":"2026-11-04T09:00:00Z"}"""),
        )

        val start = repo.facts.values.filterIsInstance<RegisterFact.RelationStarted>().single()
        assertThat(start.role).isEqualTo(CeuRole.DISPOSER)
        assertThat(start.partyId).isEqualTo(grantee)
        val end = repo.facts.values.filterIsInstance<RegisterFact.RelationEnded>().single()
        assertThat(end.accountId).isEqualTo(account)
        assertThat(end.effectiveOn).isEqualTo(LocalDate.parse("2026-11-04"))
    }

    @Test
    fun `an end for a grant never seen starting is not invented`(): Unit = runBlocking {
        consumer.consumeDelegation(
            record(
                "DelegationRevoked",
                """{"aggregateId":"${UUID.randomUUID()}","occurredAt":"2026-11-04T09:00:00Z"}""",
            ),
        )
        assertThat(repo.facts).isEmpty()
    }

    @Test
    fun `a malformed target event is rejected for the DLQ, never acked`(): Unit = runBlocking {
        assertThrows<IllegalArgumentException> {
            consumer.consumeAccount(record("AccountCreated", """{"aggregateId":"not-a-uuid"}"""))
        }
        assertThat(repo.facts).isEmpty()
    }

    @Test
    fun `party classification SYNTHETIC is recorded, CUSTOMER is real, other party events ignored`(): Unit =
        runBlocking {
            val canary = UUID.randomUUID()
            val customer = UUID.randomUUID()
            consumer.consumeParty(record("PARTY_CREATED", """{"partyId":"$canary","classification":"SYNTHETIC"}"""))
            consumer.consumeParty(record("PARTY_CREATED", """{"partyId":"$customer","classification":"CUSTOMER"}"""))
            consumer.consumeParty(record("PARTY_ERASED", """{"partyId":"${UUID.randomUUID()}"}"""))
            assertThat(repo.syntheticParties(Instant.MAX)).containsExactly(canary)
            assertThat(repo.parties).hasSize(2)
        }
}
