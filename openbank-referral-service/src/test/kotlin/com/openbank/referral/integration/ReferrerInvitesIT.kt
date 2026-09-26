// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.integration

import com.openbank.referral.application.ReferralService
import com.openbank.referral.it.ReferralPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The referrer's own invite list and the attribution conflict reasons, against real Postgres.
 *
 * Everything is seeded over JDBC under a DRAFT programme, so nothing here is visible to the
 * published-programme listing another test in this module asserts an exact size on.
 */
@QuarkusTest
@QuarkusTestResource(ReferralPostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
class ReferrerInvitesIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `a referrer sees only their own invites, newest first, with expiry derived and no referee data`() {
        val program = seedProgram()
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        val now = Instant.now()

        val expired = seedInvite(program, referrer, "ISSUED", expiresAt = now.minusSeconds(60))
        seedIssuedAudit(expired, now.minusSeconds(7_200))
        val attributed = seedInvite(program, referrer, "ATTRIBUTED", now.plusSeconds(86_400), referee, now)
        seedIssuedAudit(attributed, now.minusSeconds(3_600))
        seedReward(attributed, program, referrer, referee)
        // Someone else's invite must never appear.
        seedInvite(program, UUID.randomUUID(), "ISSUED", now.plusSeconds(86_400))

        val json = When { get("/api/v1/referrals/parties/$referrer/invites") } Then {
            statusCode(200)
            body("size()", equalTo(2))
            body("[0].id", equalTo(attributed.toString()))
            body("[0].status", equalTo("ATTRIBUTED"))
            body("[0].reward.status", equalTo("REWARD_REQUESTED"))
            body("[0].reward.amount", equalTo("500.0000"))
            body("[0].reward.currency", equalTo("CZK"))
            body("[1].id", equalTo(expired.toString()))
            body("[1].status", equalTo("EXPIRED"))
            body("[1].reward", nullValue())
        } Extract { asString() }

        assertThat(json)
            .doesNotContain(referee.toString())
            .doesNotContain("refereePartyId")
            .doesNotContain("token")
            .doesNotContain("idempotencyKey")
    }

    @Test
    fun `a party with no invites gets an empty list`() {
        When { get("/api/v1/referrals/parties/${UUID.randomUUID()}/invites") } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
    }

    @Test
    fun `attribution conflicts carry a machine-readable reason`() {
        val program = seedProgram()
        val referrer = UUID.randomUUID()
        val selfToken = "self-${UUID.randomUUID()}"
        seedInvite(program, referrer, "ISSUED", Instant.now().plusSeconds(86_400), token = selfToken)
        val expiredToken = "expired-${UUID.randomUUID()}"
        seedInvite(program, referrer, "ISSUED", Instant.now().minusSeconds(60), token = expiredToken)

        attribute(selfToken, referrer).Then {
            statusCode(409)
            body("reason", equalTo("SELF"))
        }
        attribute(expiredToken, UUID.randomUUID()).Then {
            statusCode(409)
            body("reason", equalTo("EXPIRED"))
        }
        attribute("unknown-${UUID.randomUUID()}", UUID.randomUUID()).Then { statusCode(404) }

        val first = UUID.randomUUID()
        val openToken = "open-${UUID.randomUUID()}"
        seedInvite(program, referrer, "ISSUED", Instant.now().plusSeconds(86_400), token = openToken)
        attribute(openToken, first).Then { statusCode(200) }
        attribute(openToken, first).Then { statusCode(200) }
        attribute(openToken, UUID.randomUUID()).Then {
            statusCode(409)
            body("reason", equalTo("ALREADY_ATTRIBUTED"))
        }
    }

    private fun attribute(token: String, referee: UUID) = Given {
        contentType("application/json")
        header("Idempotency-Key", "attr-${UUID.randomUUID()}")
        body("""{"refereePartyId":"$referee"}""")
    } When { post("/api/v1/referrals/invites/$token/attribute") }

    private fun seedProgram(): UUID {
        val id = UUID.randomUUID()
        exec(
            """insert into referral_program (id,name,version,reward_amount,currency,qualifying_event,
               attribution_window_ends_at,status,maker,created_at) values (?,?,?,?,?,?,?,?,?,?)""",
            id,
            "it-${UUID.randomUUID()}",
            1,
            BigDecimal("500"),
            "CZK",
            "account.opened",
            Timestamp.from(Instant.now().plusSeconds(86_400)),
            "DRAFT",
            "maker@openbank.test",
            Timestamp.from(Instant.now()),
        )
        return id
    }

    @Test
    fun `issuing under a programme that is not open is a 409 PROGRAM_UNAVAILABLE`() {
        val draft = seedProgram()
        Given {
            contentType("application/json")
            header("Idempotency-Key", "draft-${UUID.randomUUID()}")
            body("""{"referrerPartyId":"${UUID.randomUUID()}"}""")
        } When { post("/api/v1/referrals/programs/$draft/invites") } Then {
            statusCode(409)
            body("reason", equalTo("PROGRAM_UNAVAILABLE"))
        }
    }

    @Suppress("LongParameterList")
    private fun seedInvite(
        program: UUID,
        referrer: UUID,
        status: String,
        expiresAt: Instant,
        referee: UUID? = null,
        attributedAt: Instant? = null,
        token: String = UUID.randomUUID().toString(),
    ): UUID {
        val id = UUID.randomUUID()
        exec(
            """insert into referral_invite (id,program_id,token_hash,referrer_party_id,referee_party_id,status,
               expires_at,idempotency_key,attributed_at) values (?,?,?,?,?,?,?,?,?)""",
            id,
            program,
            ReferralService.hash(token),
            referrer,
            referee,
            status,
            Timestamp.from(expiresAt),
            "seed-$id",
            attributedAt?.let { Timestamp.from(it) },
        )
        return id
    }

    private fun seedIssuedAudit(invite: UUID, at: Instant) = exec(
        "insert into referral_audit_event (id,type,aggregate_id,actor,details,occurred_at) values (?,?,?,?,?,?)",
        UUID.randomUUID(),
        "INVITE_ISSUED",
        invite,
        "seed",
        "token_hash=seed",
        Timestamp.from(at),
    )

    private fun seedReward(invite: UUID, program: UUID, referrer: UUID, referee: UUID) = exec(
        """insert into referral_reward (id,invite_id,program_id,referrer_party_id,referee_party_id,
           qualification_event_id,reward_reference,amount,currency,status,created_at,requested_at)
           values (?,?,?,?,?,?,?,?,?,?,?,?)""",
        UUID.randomUUID(),
        invite,
        program,
        referrer,
        referee,
        "evt-$invite",
        "referral-$invite",
        BigDecimal("500"),
        "CZK",
        "REWARD_REQUESTED",
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()),
    )

    private fun exec(sql: String, vararg args: Any?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }
}
