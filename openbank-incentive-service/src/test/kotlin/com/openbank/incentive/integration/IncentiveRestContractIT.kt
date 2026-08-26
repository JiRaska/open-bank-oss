// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.integration

import com.openbank.incentive.it.IncentivePostgresTestResource
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
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
class IncentiveRestContractIT {
    @Inject lateinit var dataSource: DataSource

    @Suppress("LongMethod")
    @Test
    fun `published inventory reserves once under concurrency then releases commits and expires`() {
        val effectiveFrom = Instant.now().minusSeconds(60)
        val expiresAt = Instant.now().plusSeconds(86_400)
        val offerId = Given {
            contentType("application/json")
            body(
                """{
                    "name":"summer-current-account",
                    "version":1,
                    "productScope":["current-account"],
                    "effectiveFrom":"$effectiveFrom",
                    "expiresAt":"$expiresAt",
                    "totalLimit":10,
                    "perPartyLimit":2,
                    "stackingPolicy":"EXCLUSIVE"
                }
                """.trimIndent(),
            )
        } When { post("/api/v1/incentives/offers") } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
        } Extract { path<String>("ref.id") }

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then { statusCode(409) }

        Given {
            contentType("application/json")
            body("""{"codes":["SUMMER-0001","SUMMER-0002","SUMMER-0003"]}""")
        } When { post("/api/v1/incentives/offers/$offerId/codes") } Then {
            statusCode(201)
            body("imported", equalTo(3))
        }

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/submit") }
            .Then {
                statusCode(200)
                body("status", equalTo("PENDING_APPROVAL"))
            }
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then { statusCode(409) }

        TestJsonWebToken.actor = "checker@openbank.test"
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
            }

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        val futures = (1..6).map {
            pool.submit(
                Callable {
                    start.await()
                    Given {
                        contentType("application/json")
                        header("Idempotency-Key", "checkout-$offerId")
                        body("""{"code":"SUMMER-0001","partyRef":"party-1","productRef":"current-account"}""")
                    } When { post("/api/v1/incentives/offers/$offerId/reservations") } Then {
                        statusCode(201)
                        body("$", not(hasKey("codeDigest")))
                    } Extract { path<String>("id") }
                },
            )
        }
        start.countDown()
        val ids = futures.map { it.get() }
        pool.shutdown()
        assertThat(ids.toSet()).hasSize(1)
        assertThat(count("select count(*) from promo_reservation where offer_id = '$offerId'")).isEqualTo(1)
        assertThat(string("select digest from promo_code_inventory where offer_id = '$offerId' limit 1"))
            .doesNotContain("SUMMER")

        Given {
            contentType("application/json")
            header("Idempotency-Key", "checkout-$offerId")
            body("""{"code":"SUMMER-0002","partyRef":"party-2","productRef":"current-account"}""")
        } When { post("/api/v1/incentives/offers/$offerId/reservations") } Then {
            statusCode(409)
        }
        assertThat(count("select count(*) from promo_reservation where offer_id = '$offerId'")).isEqualTo(1)

        val firstId = ids.toSet().single()
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/reservations/$firstId/release") }
            .Then {
                statusCode(200)
                body("status", equalTo("RELEASED"))
            }

        val committedId = Given {
            contentType("application/json")
            header("Idempotency-Key", "checkout-commit-$offerId")
            body("""{"code":"SUMMER-0002","partyRef":"party-1","productRef":"current-account"}""")
        } When { post("/api/v1/incentives/offers/$offerId/reservations") } Then {
            statusCode(201)
        } Extract { path<String>("id") }

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/reservations/$committedId/commit") }
            .Then {
                statusCode(200)
                body("status", equalTo("COMMITTED"))
            }
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/reservations/$committedId/release") }
            .Then { statusCode(409) }

        val expiringId = Given {
            contentType("application/json")
            header("Idempotency-Key", "checkout-expire-$offerId")
            body("""{"code":"SUMMER-0003","partyRef":"party-2","productRef":"current-account"}""")
        } When { post("/api/v1/incentives/offers/$offerId/reservations") } Then {
            statusCode(201)
        } Extract { path<String>("id") }
        execute("update promo_reservation set expires_at = now() - interval '1 second' where id = '$expiringId'")

        val expiryStart = CountDownLatch(1)
        val expiryPool = Executors.newFixedThreadPool(2)
        val expiryResults = (1..2).map {
            expiryPool.submit(
                Callable {
                    expiryStart.await()
                    Given { contentType("application/json") }
                        .When { post("/api/v1/incentives/maintenance/expire") }
                        .Then { statusCode(200) }
                        .Extract { path<Int>("expired") }
                },
            )
        }
        expiryStart.countDown()
        assertThat(expiryResults.sumOf { it.get() }).isEqualTo(1)
        expiryPool.shutdown()
        assertThat(string("select status from promo_reservation where id = '$expiringId'")).isEqualTo("EXPIRED")
        assertThat(
            count(
                """select count(*) from incentive_audit_event
                    where aggregate_id = '$expiringId' and event_type = 'incentive.reservation.expired.v1'
                """.trimIndent(),
            ),
        ).isEqualTo(1)
        assertThat(count("select count(*) from incentive_audit_event where actor in ('inventory','party-1','party-2')"))
            .isZero()
        assertThat(count("select count(*) from incentive_audit_event")).isGreaterThanOrEqualTo(7)
        assertThat(
            count("select count(*) from incentive_outbox"),
        ).isEqualTo(count("select count(*) from incentive_audit_event"))
        assertThat(
            count(
                """select count(*) from incentive_outbox
                    where payload::jsonb ?& array['eventId','correlationId','aggregateId','eventType','occurredAt']
                """.trimIndent(),
            ),
        ).isEqualTo(count("select count(*) from incentive_outbox"))
    }

    private fun count(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

    private fun string(sql: String): String = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                result.getString(1)
            }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }
}
