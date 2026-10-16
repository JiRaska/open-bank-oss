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
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ROLE_MARKETING is a draft-only MGM program author: create + read the catalog and the drafts
 * work queue, but never invite/attribute/qualify (those stay OPERATOR-only via the class-level
 * @RolesAllowed the resource still carries). Publish stays OPERATOR-only in effect through the
 * maker≠checker rule — a marketing identity can never publish its own draft, and the approving
 * checker must be a different principal.
 */
@QuarkusTest
@QuarkusTestResource(ReferralPostgresTestResource::class)
@TestSecurity(user = "marketing@openbank.test", roles = ["ROLE_MARKETING"])
class ReferralMarketingRoleIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `marketing creates and lists program drafts`() {
        val id = Given {
            contentType("application/json")
            body(
                """{"name":"mkt-${UUID.randomUUID()}","version":1,"rewardAmount":25,"currency":"EUR","qualifyingEvent":"account.opened"}""",
            )
        } When { post("/api/v1/referrals/programs") } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
            body("maker", equalTo("marketing@openbank.test"))
            body("checker", nullValue())
        } Extract { path<String>("id") }

        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs") }
            .Then { statusCode(200) }

        // Read access to a single program is allowed; this draft is simply not in the catalog
        // (it lists published programs only) — a 404, not the 403 a read-forbidden role gets.
        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/$id") }
            .Then { statusCode(404) }
    }

    @Test
    fun `marketing cannot issue attribute or qualify`() {
        val programId = UUID.randomUUID()
        val token = UUID.randomUUID()

        Given {
            contentType("application/json")
            header("Idempotency-Key", "mkt-invite-$programId")
            body("""{"referrerPartyId":"${UUID.randomUUID()}"}""")
        }
            .When { post("/api/v1/referrals/programs/$programId/invites") }
            .Then { statusCode(403) }

        Given {
            contentType("application/json")
            header("Idempotency-Key", "mkt-attribute-$token")
            body("""{"refereePartyId":"${UUID.randomUUID()}"}""")
        }
            .When { post("/api/v1/referrals/invites/$token/attribute") }
            .Then { statusCode(403) }

        Given {
            contentType("application/json")
            header("Idempotency-Key", "mkt-qualify-$token")
            body("""{"eventName":"account.opened","eventId":"event-$token"}""")
        }
            .When { post("/api/v1/referrals/invites/$token/qualify") }
            .Then { statusCode(403) }
    }

    // Method-level @TestSecurity replaces the class-level identity for this test method, so
    // the whole method runs as the operator checker. The marketing-authored draft therefore
    // has to be seeded directly (same shape as ReferralRestContractIT.seedDraft) — an API
    // call here would carry the checker's identity and defeat the maker≠checker point.
    @Test
    @TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
    fun `operator checker publishes a marketing-authored draft`() {
        val id = UUID.randomUUID()
        seedMarketingDraft(id)

        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$id/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
                body("checker", equalTo("checker@openbank.test"))
            }
    }

    @Test
    fun `marketing maker cannot publish their own draft`() {
        val id = Given {
            contentType("application/json")
            body(
                """{"name":"mkt-${UUID.randomUUID()}","version":1,"rewardAmount":25,"currency":"EUR","qualifyingEvent":"account.opened"}""",
            )
        } When { post("/api/v1/referrals/programs") } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
            body("maker", equalTo("marketing@openbank.test"))
        } Extract { path<String>("id") }

        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$id/publish") }
            .Then { statusCode(409) }
    }

    @Test
    fun `marketing lists own drafts without them leaking into the published catalog`() {
        val first = createDraft()
        val second = createDraft()

        // Both drafts are visible in the drafts work queue, newest first is content-equivalent
        // here (ordering ties break on name, not creation time, so only membership is asserted).
        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/drafts") }
            .Then {
                statusCode(200)
                body("id", hasItems(first, second))
                body("status", everyItem(equalTo("DRAFT")))
            }

        // ... and absent from the published catalog. Other lifecycle tests share the database
        // and may publish their own programs, so assert absence of THESE ids, not emptiness.
        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs") }
            .Then {
                statusCode(200)
                body("id", not(hasItem(first)))
                body("id", not(hasItem(second)))
            }

        // Routing guard: 'drafts' is an exact segment and resolves to the drafts endpoint above;
        // an unknown UUID still resolves /programs/{id} and 404s rather than colliding.
        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/${UUID.randomUUID()}") }
            .Then { statusCode(404) }
    }

    // Runs as the operator checker (method-level @TestSecurity) — the publish step needs a
    // different principal than the seed's maker, so the draft is seeded directly as marketing's.
    @Test
    @TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
    fun `publishing a draft moves it out of the drafts list into the catalog`() {
        // String, not UUID: the JSON path yields Strings and a hasItem(UUID) would never match.
        val id = UUID.randomUUID().toString()
        seedMarketingDraft(UUID.fromString(id))

        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/drafts") }
            .Then {
                statusCode(200)
                body("id", hasItem(id))
            }

        Given { contentType("application/json") }
            .When { post("/api/v1/referrals/programs/$id/publish") }
            .Then { statusCode(200) }

        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/drafts") }
            .Then {
                statusCode(200)
                body("id", not(hasItem(id)))
            }

        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs") }
            .Then {
                statusCode(200)
                body("id", hasItem(id))
            }
    }

    @Test
    @TestSecurity(user = "viewer@openbank.test", roles = ["ROLE_VIEWER"])
    fun `viewer cannot list drafts`() {
        Given { contentType("application/json") }
            .When { get("/api/v1/referrals/programs/drafts") }
            .Then { statusCode(403) }
    }

    private fun createDraft(): String = Given {
        contentType("application/json")
        body(
            """{"name":"mkt-${UUID.randomUUID()}","version":1,"rewardAmount":25,"currency":"EUR","qualifyingEvent":"account.opened"}""",
        )
    } When { post("/api/v1/referrals/programs") } Then {
        statusCode(201)
        body("status", equalTo("DRAFT"))
    } Extract { path<String>("id") }

    private fun seedMarketingDraft(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into referral_program
                    (id,name,version,reward_amount,currency,qualifying_event,
                     attribution_window_ends_at,status,maker,created_at)
                    values (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "mkt-${UUID.randomUUID()}")
                statement.setInt(3, 1)
                statement.setBigDecimal(4, BigDecimal.TEN)
                statement.setString(5, "EUR")
                statement.setString(6, "account.opened")
                statement.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(86_400)))
                statement.setString(8, "DRAFT")
                statement.setString(9, "marketing@openbank.test")
                statement.setTimestamp(10, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }
}
