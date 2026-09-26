// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.integration

import com.openbank.loyalty.it.LoyaltyPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0310 D3 through the real `referral-qualified-in` channel (in memory, on a Vert.x context like
 * the Kafka connector) against a real Postgres, read back over JDBC.
 *
 * The idempotency test sends the same invite three ways — a redelivery and a re-emission under a
 * NEW event id — and is red if the consumer keys the earn on `eventId` instead of `inviteId`.
 * Every negative assertion waits on a sentinel sent AFTER the message under test, so "nothing
 * happened" is observed after the consumer provably moved past it rather than before it ran.
 */
@QuarkusTest
@QuarkusTestResource(LoyaltyPostgresTestResource::class)
class ReferralQualifiedEarnIT {
    @Inject
    lateinit var dataSource: DataSource

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Test
    fun `a qualified referral earns the referrer once per invite however often it arrives`() {
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        val invite = UUID.randomUUID()
        val first = UUID.randomUUID()

        qualified(first, invite, referrer, referee)
        qualified(first, invite, referrer, referee)
        qualified(UUID.randomUUID(), invite, referrer, referee)
        awaitSentinel()

        assertThat(referralEarns(referrer)).describedAs("one lot per invite").isEqualTo(1)
        assertThat(
            count(
                "select count(*) from leaf_ledger_entry " +
                    "where party_id = ? and correlation_event_id = ? and leaves = 250",
                referrer,
                invite,
            ),
        ).isEqualTo(1)
        assertThat(count("select count(*) from leaf_ledger_entry where party_id = ?", referee))
            .describedAs("the referee earns nothing")
            .isZero()
    }

    @Test
    fun `two different invites earn the same referrer twice`() {
        val referrer = UUID.randomUUID()
        qualified(UUID.randomUUID(), UUID.randomUUID(), referrer, UUID.randomUUID())
        qualified(UUID.randomUUID(), UUID.randomUUID(), referrer, UUID.randomUUID())
        awaitSentinel()

        assertThat(referralEarns(referrer)).isEqualTo(2)
    }

    @Test
    fun `a capped referrer is refused without wedging the channel`() {
        val referrer = UUID.randomUUID()
        seedEarn(referrer, leaves = 4_900)

        qualified(UUID.randomUUID(), UUID.randomUUID(), referrer, UUID.randomUUID())
        awaitSentinel()

        assertThat(referralEarns(referrer)).describedAs("250 over a 100 headroom is refused whole").isZero()
    }

    @Test
    fun `a payload without a referrer is skipped and the channel keeps flowing`() {
        source().send(
            """{"eventType":"Qualified","eventId":"${UUID.randomUUID()}","inviteId":"${UUID.randomUUID()}"}""",
        )
        source().send("not json at all")
        awaitSentinel()
    }

    private fun source(): InMemorySource<String> =
        connector.source<String>("referral-qualified-in").also { it.runOnVertxContext(true) }

    private fun qualified(eventId: UUID, invite: UUID, referrer: UUID, referee: UUID) {
        source().send(
            """{"eventId":"$eventId","occurredAt":"${Instant.now()}","programId":"${UUID.randomUUID()}",""" +
                """"inviteId":"$invite","referrerPartyId":"$referrer","refereePartyId":"$referee",""" +
                """"qualificationEventId":"${UUID.randomUUID()}","eventType":"Qualified"}""",
        )
    }

    /** A qualified referral for a fresh party; once its lot exists, everything sent before it was consumed. */
    private fun awaitSentinel() {
        val sentinel = UUID.randomUUID()
        qualified(UUID.randomUUID(), UUID.randomUUID(), sentinel, UUID.randomUUID())
        val deadline = Instant.now().plus(Duration.ofSeconds(15))
        while (referralEarns(sentinel) == 0L && Instant.now().isBefore(deadline)) Thread.sleep(100)
        assertThat(referralEarns(sentinel)).describedAs("sentinel consumed").isEqualTo(1)
    }

    private fun referralEarns(party: UUID): Long = count(
        "select count(*) from leaf_ledger_entry where party_id = ? and earn_source_id = 'QUALIFIED_REFERRAL'",
        party,
    )

    private fun seedEarn(party: UUID, leaves: Int) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "insert into leaf_ledger_entry (id, party_id, entry_type, leaves, remaining_leaves, earn_source_id, " +
                    "rule_version, correlation_event_id, occurred_at, expires_at) " +
                    "values (?, ?, 'EARN', ?, ?, 'SAVINGS_GOAL_REACHED', 'v1', ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, party)
                ps.setInt(3, leaves)
                ps.setInt(4, leaves)
                ps.setObject(5, UUID.randomUUID())
                ps.setTimestamp(6, Timestamp.from(Instant.now()))
                ps.setTimestamp(7, Timestamp.from(Instant.now().plus(Duration.ofDays(700))))
                ps.executeUpdate()
            }
            if (!c.autoCommit) c.commit()
        }
    }

    private fun count(sql: String, vararg params: kotlin.Any): Long = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }
}
