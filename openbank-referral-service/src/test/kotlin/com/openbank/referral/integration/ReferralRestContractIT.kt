package com.openbank.referral.integration

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
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(ReferralPostgresTestResource::class)
@TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
class ReferralRestContractIT {
    @Inject
    lateinit var dataSource: DataSource

    // LongMethod: one continuous lifecycle (draft -> publish -> issue -> attribute -> qualify ->
    // idempotent replay) — splitting it into separate @Test methods would let them run out of
    // order or in isolation, hiding a break in the sequence this contract actually depends on.
    @Suppress("LongMethod")
    @Test
    fun `checker publication enables invite attribution qualification and idempotent replay`() {
        val selfApprovalId = Given {
            contentType("application/json")
            body(
                """{"name":"it-${UUID.randomUUID()}","version":1,"rewardAmount":10,"currency":"EUR","qualifyingEvent":"account.opened"}""",
            )
        } When { post("/api/v1/referrals/programs") } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
        } Extract { path<String>("id") }

        Given { contentType("application/json") } When { get("/api/v1/referrals/programs/$selfApprovalId") } Then {
            statusCode(404)
        }

        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$selfApprovalId/publish") }
            .Then { statusCode(409) }

        val programId = UUID.randomUUID()
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        seedDraft(programId)

        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$programId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
                body("checker", equalTo("checker@openbank.test"))
            }

        Given { contentType("application/json") } When { get("/api/v1/referrals/programs/$programId") } Then {
            statusCode(200)
            body("id", equalTo(programId.toString()))
            body("version", equalTo(1))
        }

        val expiredProgramId = UUID.randomUUID()
        seedDraft(expiredProgramId, Instant.now().minusSeconds(1))
        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$expiredProgramId/publish") }
            .Then { statusCode(200) }
        Given { contentType("application/json") } When { get("/api/v1/referrals/programs/$expiredProgramId") } Then {
            statusCode(404)
        }

        val inviteToken = Given {
            contentType("application/json")
            header("Idempotency-Key", "invite-$programId")
            body("""{"referrerPartyId":"$referrer"}""")
        } When { post("/api/v1/referrals/programs/$programId/invites") } Then {
            statusCode(201)
            body("status", equalTo("ISSUED"))
            body("token", notNullValue())
        } Extract { path<String>("token") }

        Given { contentType("application/json") }
            .header("Idempotency-Key", "attribute-$programId")
            .body("""{"refereePartyId":"$referee"}""")
            .When { post("/api/v1/referrals/invites/$inviteToken/attribute") }
            .Then {
                statusCode(200)
                body("status", equalTo("ATTRIBUTED"))
                body("refereePartyId", equalTo(referee.toString()))
            }

        val rewardReference = Given {
            contentType("application/json")
            header("Idempotency-Key", "qualify-$programId")
            body("""{"eventName":"account.opened","eventId":"event-$programId"}""")
        } When { post("/api/v1/referrals/invites/$inviteToken/qualify") } Then {
            statusCode(202)
            body("status", equalTo("REWARD_REQUESTED"))
            body("rewardReference", notNullValue())
        } Extract { path<String>("rewardReference") }

        assertOutbox(programId, expectedRows = 2)

        Given { contentType("application/json") }
            .header("Idempotency-Key", "qualify-replay-$programId")
            .body("""{"eventName":"account.opened","eventId":"event-$programId"}""")
            .When { post("/api/v1/referrals/invites/$inviteToken/qualify") }
            .Then {
                statusCode(202)
                body("rewardReference", equalTo(rewardReference))
            }

        // Replay returns the original reward and must not enqueue duplicate money-path events.
        assertOutbox(programId, expectedRows = 2)

        Given { contentType("application/json") }
            .header("Idempotency-Key", "invite-$programId")
            .body("""{"referrerPartyId":"$referrer"}""")
            .When { post("/api/v1/referrals/programs/$programId/invites") }
            .Then { statusCode(409) }
    }

    @Test
    fun `published programme catalogue exposes only unexpired immutable references`() {
        val alpha = UUID.randomUUID()
        val zeta = UUID.randomUUID()
        val draft = UUID.randomUUID()
        val expired = UUID.randomUUID()
        seedDraft(alpha, name = "catalogue-alpha", version = 2)
        seedDraft(zeta, name = "catalogue-zeta", version = 1)
        seedDraft(draft, name = "catalogue-draft", version = 1)
        seedDraft(expired, Instant.now().minusSeconds(1), name = "catalogue-expired", version = 1)

        listOf(alpha, zeta, expired).forEach { id ->
            Given { contentType("application/json") } When { post("/api/v1/referrals/programs/$id/publish") } Then {
                statusCode(200)
            }
        }

        Given { contentType("application/json") } When { get("/api/v1/referrals/programs") } Then {
            statusCode(200)
            body("items.find { it.id == '%s' }.name".format(alpha), equalTo("catalogue-alpha"))
            body("items.find { it.id == '%s' }.version".format(alpha), equalTo(2))
            body("items.find { it.id == '%s' }.name".format(zeta), equalTo("catalogue-zeta"))
            body("items.find { it.id == '%s' }".format(draft), nullValue())
            body("items.find { it.id == '%s' }".format(expired), nullValue())
            body("items[0].rewardAmount", nullValue())
            body("items[0].qualifyingEvent", nullValue())
        }
    }

    private fun assertOutbox(programId: UUID, expectedRows: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select event_type, status, payload from referral_outbox where aggregate_id in " +
                    "(select id from referral_reward where program_id = ?) order by event_type",
            ).use { statement ->
                statement.setObject(1, programId)
                val rows = buildList {
                    statement.executeQuery().use { result ->
                        while (result.next()) {
                            add(Triple(result.getString(1), result.getString(2), result.getString(3)))
                        }
                    }
                }
                assertThat(rows).hasSize(expectedRows)
                assertThat(rows.map { it.first }).containsExactly(
                    "QualifiedV2",
                    "RewardRequestedV2",
                )
                assertThat(rows.map { it.second }).containsOnly("PENDING")
                assertThat(rows.map { it.third }).allMatch { it.contains("\"eventId\"") }
                assertThat(rows.map { it.third }).allMatch {
                    it.contains("\"schemaVersion\":2") && it.contains("\"programVersion\":1")
                }
                assertThat(rows.map { it.third }).allMatch {
                    !it.contains("referrerPartyId") && !it.contains("refereePartyId")
                }
            }
        }
    }

    private fun seedDraft(
        id: UUID,
        attributionWindowEndsAt: Instant = Instant.now().plusSeconds(86_400),
        name: String = "it-${UUID.randomUUID()}",
        version: Int = 1,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into referral_program
                    (id,name,version,reward_amount,currency,qualifying_event,
                     attribution_window_ends_at,status,maker,created_at)
                    values (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, name)
                statement.setInt(3, version)
                statement.setBigDecimal(4, BigDecimal.TEN)
                statement.setString(5, "EUR")
                statement.setString(6, "account.opened")
                statement.setTimestamp(7, Timestamp.from(attributionWindowEndsAt))
                statement.setString(8, "DRAFT")
                statement.setString(9, "maker@openbank.test")
                statement.setTimestamp(10, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }
}
