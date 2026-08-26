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
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
class IncentiveRestContractIT {
    @Inject lateinit var dataSource: DataSource

    @Suppress("LongMethod")
    @Test
    fun `published inventory reserves once under concurrency then releases commits and expires`() {
        val offerId = UUID.randomUUID()
        seedPendingOffer(offerId, perPartyLimit = 2)

        Given {
            contentType("application/json")
            body("""{"codes":["SUMMER-0001","SUMMER-0002","SUMMER-0003"]}""")
        } When { post("/api/v1/incentives/offers/$offerId/codes") } Then {
            statusCode(201)
            body("imported", equalTo(3))
        }

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
            .Then { statusCode(400) }

        val expiringId = Given {
            contentType("application/json")
            header("Idempotency-Key", "checkout-expire-$offerId")
            body("""{"code":"SUMMER-0003","partyRef":"party-2","productRef":"current-account"}""")
        } When { post("/api/v1/incentives/offers/$offerId/reservations") } Then {
            statusCode(201)
        } Extract { path<String>("id") }
        execute("update promo_reservation set expires_at = now() - interval '1 second' where id = '$expiringId'")

        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/maintenance/expire") }
            .Then {
                statusCode(200)
                body("expired", equalTo(1))
            }
        assertThat(string("select status from promo_reservation where id = '$expiringId'")).isEqualTo("EXPIRED")
        assertThat(count("select count(*) from incentive_audit_event")).isGreaterThanOrEqualTo(7)
        assertThat(
            count("select count(*) from incentive_outbox"),
        ).isEqualTo(count("select count(*) from incentive_audit_event"))
        assertThat(
            count(
                """select count(*) from incentive_outbox
                    where payload::jsonb ?& array['eventId','aggregateId','eventType','occurredAt']
                """.trimIndent(),
            ),
        ).isEqualTo(count("select count(*) from incentive_outbox"))
    }

    private fun seedPendingOffer(id: UUID, perPartyLimit: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into incentive_offer
                    (id,name,version,product_scope,effective_from,expires_at,total_limit,per_party_limit,
                     stacking_policy,status,maker,created_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "summer-$id")
                statement.setInt(3, 1)
                statement.setString(4, "current-account")
                statement.setTimestamp(5, Timestamp.from(Instant.now().minusSeconds(60)))
                statement.setTimestamp(6, Timestamp.from(Instant.now().plusSeconds(86_400)))
                statement.setInt(7, 10)
                statement.setInt(8, perPartyLimit)
                statement.setString(9, "EXCLUSIVE")
                statement.setString(10, "PENDING_APPROVAL")
                statement.setString(11, "maker@openbank.test")
                statement.setTimestamp(12, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
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
