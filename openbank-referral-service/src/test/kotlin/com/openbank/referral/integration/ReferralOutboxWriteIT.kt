// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.integration

import com.openbank.referral.it.ReferralPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Closes #7190: `ReferralService.qualifyInvite` used to hand `Qualified` and `RewardRequested` to
 * `UnwiredReferralEventPublisher`, which dropped both — every reward event was lost with no
 * metric, no log, and no record a customer's referral reward was ever requested downstream.
 *
 * Only a real-DB integration test can prove the fix: a unit test that mocks
 * `ReferralRewardRepository` cannot tell whether the outbox write actually landed in the SAME
 * transaction as the reward row, which is the whole point (`KycOutboxWriteIT`,
 * `LendingOutboxWriteIT` pattern). And the reactive repository cannot be called directly from a
 * bare `@QuarkusTest` thread (`Panache.withTransaction` throws "No current Vertx context found"
 * outside a real request) — so this drives the REST endpoints with RestAssured and reads the
 * `referral_outbox` rows back over plain JDBC.
 *
 * The dispatcher is switched off for the duration so it cannot drain the rows to SENT before the
 * assertion observes them — the claim under test is that the row is WRITTEN in the state-change
 * transaction, not what happens to it afterwards.
 */
@QuarkusTest
@QuarkusTestResource(ReferralOutboxWriteIT.DispatcherOffResource::class)
@QuarkusTestResource(ReferralPostgresTestResource::class)
@TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
class ReferralOutboxWriteIT {

    class DispatcherOffResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun seedDraft(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into referral_program
                    (id,name,version,reward_amount,currency,qualifying_event,
                     attribution_window_ends_at,status,maker,created_at)
                    values (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "outbox-it-${UUID.randomUUID()}")
                statement.setInt(3, 1)
                statement.setBigDecimal(4, BigDecimal.TEN)
                statement.setString(5, "EUR")
                statement.setString(6, "account.opened")
                statement.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(86_400)))
                statement.setString(8, "DRAFT")
                statement.setString(9, "maker@openbank.test")
                statement.setTimestamp(10, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }

    private fun outboxRows(rewardId: UUID): List<Pair<String, String>> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT event_type, status FROM referral_outbox WHERE aggregate_id = ? ORDER BY created_at",
        ).use { ps ->
            ps.setObject(1, rewardId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<Pair<String, String>>()
            while (rs.next()) rows += rs.getString("event_type") to rs.getString("status")
            rows
        }
    }

    @Test
    fun `qualifying an invite writes exactly one Qualified and one RewardRequested to the outbox`() {
        val programId = UUID.randomUUID()
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        seedDraft(programId)

        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$programId/publish") }
            .Then { statusCode(200) }

        val inviteToken = Given {
            contentType("application/json")
            header("Idempotency-Key", "invite-$programId")
            body("""{"referrerPartyId":"$referrer"}""")
        } When { post("/api/v1/referrals/programs/$programId/invites") } Then {
            statusCode(201)
        } Extract { path<String>("token") }

        Given { contentType("application/json") }
            .header("Idempotency-Key", "attribute-$programId")
            .body("""{"refereePartyId":"$referee"}""")
            .When { post("/api/v1/referrals/invites/$inviteToken/attribute") }
            .Then { statusCode(200) }

        val rewardId = Given {
            contentType("application/json")
            header("Idempotency-Key", "qualify-$programId")
            body("""{"eventName":"account.opened","eventId":"event-$programId"}""")
        } When { post("/api/v1/referrals/invites/$inviteToken/qualify") } Then {
            statusCode(202)
            body("status", equalTo("REWARD_REQUESTED"))
        } Extract { path<String>("id") }

        val rows = outboxRows(UUID.fromString(rewardId))
        assertThat(rows).describedAs("referral_outbox rows for reward %s", rewardId).hasSize(2)
        assertThat(rows.map { it.first }).containsExactlyInAnyOrder("Qualified", "RewardRequested")
        assertThat(rows).allSatisfy { (_, status) -> assertThat(status).isEqualTo("PENDING") }

        // Idempotent replay must not write a second pair of events for the same qualification.
        Given { contentType("application/json") }
            .header("Idempotency-Key", "qualify-replay-$programId")
            .body("""{"eventName":"account.opened","eventId":"event-$programId"}""")
            .When { post("/api/v1/referrals/invites/$inviteToken/qualify") }
            .Then { statusCode(202) }

        assertThat(outboxRows(UUID.fromString(rewardId))).hasSize(2)
    }
}
