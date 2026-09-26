// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.analytics.application.port.out.DeadLetterRecord
import com.openbank.analytics.application.port.out.DeadLetterSink
import com.openbank.libs.analytics.AnalyticsEnvelope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The party-bearing guard in [AnalyticsConsumer] (#8792 acceptance 2).
 *
 * `silver_party_accounts` is built from `AccountCreated` and `SegmentRule.HasAccount` resolves
 * cohorts through it, so an `AccountCreated` that arrives with no `partyId` writes an ownerless
 * account and silently shrinks every cohort by one — the defect #2891 recorded. Nothing errors,
 * and an empty cohort is indistinguishable from "nobody matched".
 *
 * **The third test is the one that makes the other two worth having.** The criterion as originally
 * written asked for "an ACCOUNT event published without `partyId`" to be quarantined, and
 * implementing that literally would discard the balance and hold stream: measured on the sandbox
 * warehouse 2026-09-10, 418 of 437 ACCOUNT events carry no party BY DESIGN (`BALANCE_UPDATED` 205,
 * `HOLD_PLACED` 98, `HOLD_RELEASED` 97, `AccountStatusChanged` 18), against 19 `AccountCreated`
 * that all carry one. So a guard keyed on the aggregate type passes both tests below while
 * destroying 96% of the stream, and only `a party-less ACCOUNT-level fact is still ingested`
 * can tell the two implementations apart.
 */
class PartyBearingEventGuardTest {

    private val mapper = ObjectMapper()

    private class CapturingDlq : DeadLetterSink {
        val records = mutableListOf<DeadLetterRecord>()
        override suspend fun quarantine(record: DeadLetterRecord) {
            records += record
        }
    }

    private class RecordingSink : AnalyticsSink {
        val written = mutableListOf<AnalyticsEnvelope>()
        override suspend fun write(envelope: AnalyticsEnvelope) {
            written += envelope
        }
    }

    private class Outcome(payload: String) {
        var acked = false
        var nacked: Throwable? = null
        val message: Message<String> = Message.of(
            payload,
            {
                acked = true
                CompletableFuture.completedFuture(null) as CompletionStage<Void>
            },
            { t: Throwable ->
                nacked = t
                CompletableFuture.completedFuture(null) as CompletionStage<Void>
            },
        )
    }

    private fun consumerWith(sink: AnalyticsSink, dlq: DeadLetterSink) = AnalyticsConsumer().apply {
        this.sink = sink
        this.deadLetters = dlq
        objectMapper = mapper
        clock = Clock.systemUTC()
    }

    private fun accountCreated(partyId: String?) = buildString {
        append("""{"eventId":"11111111-1111-1111-1111-111111111111",""")
        append(""""aggregateType":"ACCOUNT","aggregateId":"acc-1",""")
        append(""""eventType":"AccountCreated","occurredAt":"2026-01-01T00:00:00Z"""")
        if (partyId != null) append(""","partyId":"$partyId"""")
        append("}")
    }

    /** A balance fact: ACCOUNT-typed, and party-less because a party is not part of it. */
    private val balanceUpdated =
        """{"eventId":"22222222-2222-2222-2222-222222222222","aggregateType":"ACCOUNT",""" +
            """"aggregateId":"acc-1","eventType":"BALANCE_UPDATED","occurredAt":"2026-01-01T00:00:00Z",""" +
            """"currency":"CZK","bookedAmount":"100.00"}"""

    @Test
    fun `an AccountCreated with no partyId is quarantined instead of written`(): Unit = runBlocking {
        val sink = RecordingSink()
        val dlq = CapturingDlq()
        val outcome = Outcome(accountCreated(partyId = null))

        consumerWith(sink, dlq).consume(outcome.message)

        assertThat(dlq.records).hasSize(1)
        assertThat(dlq.records.single().error).contains("AccountCreated", "partyId")
        // Not written: an ownerless row in bronze is what silver would then build the cohort from.
        assertThat(sink.written).isEmpty()
        // ACKed, not nacked — a producer that omitted the field will omit it on every replay too.
        assertThat(outcome.acked).isTrue()
        assertThat(outcome.nacked).isNull()
    }

    @Test
    fun `an AccountCreated carrying a partyId is written normally`(): Unit = runBlocking {
        val sink = RecordingSink()
        val dlq = CapturingDlq()
        val outcome = Outcome(accountCreated(partyId = "33333333-3333-3333-3333-333333333333"))

        consumerWith(sink, dlq).consume(outcome.message)

        assertThat(sink.written).hasSize(1)
        assertThat(sink.written.single().eventType).isEqualTo("AccountCreated")
        assertThat(dlq.records).isEmpty()
        assertThat(outcome.acked).isTrue()
    }

    @Test
    fun `a party-less ACCOUNT-level fact is still ingested`(): Unit = runBlocking {
        val sink = RecordingSink()
        val dlq = CapturingDlq()
        val outcome = Outcome(balanceUpdated)

        consumerWith(sink, dlq).consume(outcome.message)

        // This is the regression guard: widen the check from the event type to the aggregate type
        // and this single assertion is what goes red, while both tests above stay green.
        assertThat(sink.written).hasSize(1)
        assertThat(sink.written.single().eventType).isEqualTo("BALANCE_UPDATED")
        assertThat(dlq.records).isEmpty()
        assertThat(outcome.acked).isTrue()
    }
}
