// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.integration

import com.openbank.incentive.infrastructure.outbox.IncentiveOutboxDispatcher
import com.openbank.incentive.infrastructure.persistence.OutboxEntities
import com.openbank.incentive.it.IncentivePostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@QuarkusTestResource(IncentiveRestContractIT.InMemoryKafkaResource::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR"])
class IncentiveRestContractIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("incentive-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var meterRegistry: MeterRegistry

    @Inject lateinit var dispatcher: IncentiveOutboxDispatcher

    @Inject lateinit var outbox: OutboxEntities

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Suppress("LongMethod")
    @Test
    fun `published inventory reserves once under concurrency then releases commits and expires`() {
        val publicationsBefore = meterRegistry.counter("openbank.incentive.offers.published").count()
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
        assertThat(meterRegistry.counter("openbank.incentive.offers.published").count())
            .isEqualTo(publicationsBefore)

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
        assertThat(meterRegistry.counter("openbank.incentive.offers.published").count())
            .isEqualTo(publicationsBefore)

        TestJsonWebToken.actor = "checker@openbank.test"
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
            }
        assertThat(meterRegistry.counter("openbank.incentive.offers.published").count())
            .isEqualTo(publicationsBefore + 1.0)

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

        val eventCount = count("select count(*) from incentive_outbox")
        onVertxContext { dispatcher.dispatchForTest() }
        @Suppress("UNCHECKED_CAST")
        val published = connector.sink<String>("incentive-events-out").received().map { it as Message<String> }
        assertThat(published)
            .describedAs(
                string("select string_agg(status || ':' || coalesce(last_error, ''), ',') from incentive_outbox"),
            )
            .hasSize(eventCount.toInt())
        published.forEach { message ->
            val metadata = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow()
            val eventId = message.payload.substringAfter("\"eventId\":\"").substringBefore('"')
            val aggregateId = message.payload.substringAfter("\"aggregateId\":\"").substringBefore('"')
            val eventType = message.payload.substringAfter("\"eventType\":\"").substringBefore('"')
            assertThat(metadata.key).isEqualTo(aggregateId)
            assertThat(header(metadata, OutboxKafkaHeaders.HEADER_EVENT_ID))
                .isEqualTo(eventId)
            assertThat(header(metadata, OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY)).isEqualTo(eventId)
            assertThat(header(metadata, OutboxKafkaHeaders.HEADER_EVENT_TYPE)).isEqualTo(eventType)
            assertThat(eventType).isIn(CONTRACTED_EVENT_TYPES)
        }
        assertThat(count("select count(*) from incentive_outbox where status = 'SENT'")).isEqualTo(eventCount)
        onVertxContext { dispatcher.dispatchForTest() }
        assertThat(connector.sink<String>("incentive-events-out").received()).hasSize(eventCount.toInt())

        val raceOfferId = Given {
            contentType("application/json")
            body(
                """{"name":"claim-race","version":1,"productScope":["current-account"],
                    "effectiveFrom":"$effectiveFrom","expiresAt":"$expiresAt","totalLimit":1,
                    "perPartyLimit":1,"stackingPolicy":"EXCLUSIVE"}
                """.trimIndent(),
            )
        } When { post("/api/v1/incentives/offers") } Then { statusCode(201) } Extract { path<String>("ref.id") }
        val raceEventId = string(
            "select id::text from incentive_outbox where aggregate_id = '$raceOfferId'",
        )
        val firstClaim = onVertxContext { outbox.claimWithToken(1, Duration.ofMinutes(2)).single() }
        assertThat(firstClaim.entry.eventId.toString()).isEqualTo(raceEventId)
        execute("update incentive_outbox set claimed_at = now() - interval '3 minutes' where id = '$raceEventId'")
        val secondClaim = onVertxContext { outbox.claimWithToken(1, Duration.ofMinutes(2)).single() }
        assertThat(onVertxContext { outbox.markSentClaimed(secondClaim, Instant.now()) }).isTrue()
        assertThat(onVertxContext { outbox.markFailedClaimed(firstClaim, "stale worker", Instant.now()) }).isNull()
        assertThat(string("select status from incentive_outbox where id = '$raceEventId'")).isEqualTo("SENT")
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun header(metadata: OutgoingKafkaRecordMetadata<*>, name: String): String =
        String(metadata.headers.lastHeader(name).value(), StandardCharsets.UTF_8)

    companion object {
        private val CONTRACTED_EVENT_TYPES =
            setOf(
                "incentive.offer.created.v1",
                "incentive.offer.submitted.v1",
                "incentive.offer.published.v1",
                "incentive.codes.imported.v1",
                "incentive.reservation.created.v1",
                "incentive.reservation.committed.v1",
                "incentive.reservation.released.v1",
                "incentive.reservation.expired.v1",
            )
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
