// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.integration

import com.openbank.referral.it.ReferralPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0310 D1, end to end: `AccountCreated` arrives on the real `account-created-in` channel (in
 * memory, on a Vert.x context like the Kafka connector), attribution goes through the real REST
 * route, and every assertion reads Postgres over plain JDBC.
 *
 * The ordering test is the reason this exists. A referee opens the account during onboarding,
 * BEFORE redeeming the code, and a consumer that only qualifies already-attributed invites passes
 * every other test here while missing the common case. Removing the attribution-time hook
 * (`ReferralResource.attribute` calling `attributeInvite` directly) turns
 * `an account opened during onboarding qualifies when the referee redeems the code later` red.
 */
@QuarkusTest
@QuarkusTestResource(ReferralPostgresTestResource::class)
@TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
class ReferralQualificationIT {
    @Inject
    lateinit var dataSource: DataSource

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    private val programs = mutableListOf<UUID>()

    /** Published programmes are a shared catalogue other ITs count; retire ours afterwards. */
    @AfterEach
    fun retirePrograms() {
        programs.forEach { execute("update referral_program set status = 'EXPIRED' where id = ?", it) }
        programs.clear()
    }

    @Test
    fun `an account opened during onboarding qualifies when the referee redeems the code later`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        val token = issueInvite(programId, UUID.randomUUID())
        val eventId = UUID.randomUUID().toString()

        accountCreated(eventId, referee, Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", eventId, expected = 1)
        assertThat(rewardsForReferee(referee)).describedAs("nothing to qualify before attribution").isZero()

        attribute(token, referee)

        assertThat(
            count(
                "select count(*) from referral_reward where referee_party_id = ? and qualification_event_id = ?",
                referee,
                eventId,
            ),
        )
            .describedAs("attribution must qualify on the fact recorded earlier")
            .isEqualTo(1)
        assertThat(outboxTypesForReferee(referee)).containsExactlyInAnyOrder("Qualified", "RewardRequested")
    }

    @Test
    fun `an account opened after attribution qualifies when the event arrives`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        val token = issueInvite(programId, UUID.randomUUID())
        attribute(token, referee)
        assertThat(rewardsForReferee(referee)).isZero()

        accountCreated(UUID.randomUUID().toString(), referee, Instant.now())

        awaitCount("select count(*) from referral_reward where referee_party_id = ?", referee, expected = 1)
    }

    @Test
    fun `an account opened before the invite was issued never qualifies it`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        val eventId = UUID.randomUUID().toString()
        accountCreated(eventId, referee, Instant.now().minus(Duration.ofHours(1)))
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", eventId, expected = 1)

        val token = issueInvite(programId, UUID.randomUUID())
        attribute(token, referee)

        assertThat(rewardsForReferee(referee)).isZero()
        assertThat(
            count(
                "select count(*) from referral_audit_event where type = 'QUALIFICATION_REJECTED' and details like ?",
                "reason=FACT_BEFORE_INVITE_ISSUED %eventId=$eventId",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `a referee redeeming two codes is rewarded once and a redelivery changes nothing`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        attribute(issueInvite(programId, UUID.randomUUID()), referee)
        attribute(issueInvite(programId, UUID.randomUUID()), referee)
        val eventId = UUID.randomUUID().toString()
        val openedAt = Instant.now()
        val rejected = "select count(*) from referral_audit_event " +
            "where type = 'QUALIFICATION_REJECTED' and details like ?"
        val pattern = "reason=REFEREE_ALREADY_REWARDED %eventId=$eventId"

        accountCreated(eventId, referee, openedAt)
        awaitCount(rejected, pattern, expected = 1)
        assertThat(rewardsForReferee(referee)).isEqualTo(1)

        accountCreated(eventId, referee, openedAt)
        awaitCount(rejected, pattern, expected = 2)

        assertThat(rewardsForReferee(referee)).describedAs("redelivery must not add a reward").isEqualTo(1)
        assertThat(count("select count(*) from referral_qualifying_fact where party_id = ?", referee)).isEqualTo(1)
    }

    @Test
    fun `a second account for the same party is not a second qualification`() {
        val programId = publishedProgram()
        val referee = UUID.randomUUID()
        val first = UUID.randomUUID().toString()
        accountCreated(first, referee, Instant.now().minus(Duration.ofHours(1)))
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", first, expected = 1)

        val token = issueInvite(programId, UUID.randomUUID())
        attribute(token, referee)
        val second = UUID.randomUUID().toString()
        accountCreated(second, referee, Instant.now())
        // A sentinel on the same channel proves the second account's record has been consumed.
        val sentinel = UUID.randomUUID().toString()
        accountCreated(sentinel, UUID.randomUUID(), Instant.now())
        awaitCount("select count(*) from referral_qualifying_fact where event_id = ?", sentinel, expected = 1)

        assertThat(count("select count(*) from referral_qualifying_fact where event_id = ?", second)).isZero()
        assertThat(rewardsForReferee(referee)).isZero()
    }

    private fun publishedProgram(): UUID {
        val id = UUID.randomUUID()
        execute(
            """insert into referral_program
                (id,name,version,reward_amount,currency,qualifying_event,attribution_window_ends_at,status,maker,created_at)
                values (?,?,?,?,?,?,?,?,?,?)""",
            id,
            "qualification-it-$id",
            1,
            BigDecimal.TEN,
            "CZK",
            "account.opened",
            Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
            "DRAFT",
            "maker@openbank.test",
            Timestamp.from(Instant.now()),
        )
        programs += id
        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$id/publish") }
            .Then { statusCode(200) }
        return id
    }

    private fun issueInvite(programId: UUID, referrer: UUID): String = Given {
        contentType("application/json")
        header("Idempotency-Key", "invite-${UUID.randomUUID()}")
        body("""{"referrerPartyId":"$referrer"}""")
    } When { post("/api/v1/referrals/programs/$programId/invites") } Then {
        statusCode(201)
    } Extract { path("token") }

    private fun attribute(token: String, referee: UUID) {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "attribute-${UUID.randomUUID()}")
            body("""{"refereePartyId":"$referee"}""")
        } When { post("/api/v1/referrals/invites/$token/attribute") } Then { statusCode(200) }
    }

    private fun accountCreated(eventId: String, partyId: UUID, occurredAt: Instant) {
        val source: InMemorySource<String> = connector.source("account-created-in")
        source.runOnVertxContext(true)
        source.send(
            """{"eventId":"$eventId","aggregateId":"${UUID.randomUUID()}","aggregateType":"Account",""" +
                """"eventType":"AccountCreated","version":0,"accountNumber":"123","accountType":"CURRENT",""" +
                """"partyId":"$partyId","productId":"${UUID.randomUUID()}","currency":"CZK",""" +
                """"occurredAt":"$occurredAt","sourceService":"account-service"}""",
        )
    }

    private fun rewardsForReferee(referee: UUID): Long =
        count("select count(*) from referral_reward where referee_party_id = ?", referee)

    private fun outboxTypesForReferee(referee: UUID): List<String> = dataSource.connection.use { c ->
        c.prepareStatement(
            "select o.event_type from referral_outbox o join referral_reward r on r.id = o.aggregate_id " +
                "where r.referee_party_id = ?",
        ).use { ps ->
            ps.setObject(1, referee)
            val rs = ps.executeQuery()
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    private fun awaitCount(sql: String, param: kotlin.Any, expected: Long) {
        val deadline = Instant.now().plus(AWAIT)
        var last = count(sql, param)
        while (last != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_MS)
            last = count(sql, param)
        }
        assertThat(last).describedAs("awaited `%s` for %s", sql, param).isEqualTo(expected)
    }

    private fun count(sql: String, vararg params: kotlin.Any): Long = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            val rs = ps.executeQuery()
            rs.next()
            rs.getLong(1)
        }
    }

    private fun execute(sql: String, vararg params: kotlin.Any) {
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeUpdate()
            }
            if (!c.autoCommit) c.commit()
        }
    }

    private companion object {
        val AWAIT: Duration = Duration.ofSeconds(15)
        const val POLL_MS = 100L
    }
}
