// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.AccountPartyPort
import com.openbank.aml.application.port.out.MovementCountQuery
import com.openbank.aml.application.port.out.MovementCounterReader
import com.openbank.aml.domain.monitoring.MovementDirection
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ADR-0371 slice 1 end to end: real broker -> the production consumers -> real Postgres -> the
 * attribution sweep dispatched by the REAL scheduler -> counters.
 *
 * Nothing calls a consumer or the sweep directly. A direct call would supply the Vert.x context the
 * connector and the scheduler must supply themselves, and the in-memory connector has no
 * dead-letter queue at all, so neither could show what this class claims:
 *  - a series of booked movements increments exactly the right counters, per currency;
 *  - a replayed booking — re-emitted under a FRESH event id, as a ledger replay does — changes nothing;
 *  - a poison record lands on this service's dead-letter topic and the channel keeps consuming.
 *
 * Only the account authority is stubbed ([StubAccountParty]). All ids are literals: a test profile
 * loads in another classloader, so a randomised companion value would differ between stub and test.
 */
@QuarkusTest
@TestProfile(TransactionMonitoringIntakeIT.IntakeProfile::class)
class TransactionMonitoringIntakeIT {

    class IntakeProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaRedisTestResource::class.java))

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.aml.monitoring.attribution.interval" to "1s",
            "openbank.aml.monitoring.attribution.initial-delay" to "1s",
            "openbank.aml.monitoring.attribution.origin-grace" to "PT3S",
            "openbank.aml.party-resolution.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            "mp.messaging.incoming.ledger-events-in.auto.offset.reset" to "earliest",
            "mp.messaging.incoming.transaction-events-in.auto.offset.reset" to "earliest",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubAccountParty::class.java)
    }

    /** The account authority. An account missing from the map is "not known right now". */
    @Alternative
    @ApplicationScoped
    class StubAccountParty : AccountPartyPort {
        override suspend fun findPartyByAccountId(accountId: UUID): UUID? = OWNERS[accountId]
    }

    @Inject
    lateinit var pool: PgPool

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var reader: MovementCounterReader

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private data class Counter(
        val currency: String,
        val direction: String,
        val rail: String,
        val bucket: Instant,
        val band: String,
        val count: Long,
    )

    private fun counters(partyId: UUID): Set<Counter> = onEventLoop {
        pool.preparedQuery(
            """
            SELECT currency, direction, rail, bucket_start, band_code, movement_count, scheme_revision, bucket_seconds
              FROM aml_movement_counter WHERE party_id = $1
            """.trimIndent(),
        ).execute(Tuple.of(partyId)).awaitSuspending().map {
            assertThat(it.getInteger("scheme_revision")).isEqualTo(1)
            assertThat(it.getInteger("bucket_seconds")).isEqualTo(3600)
            Counter(
                it.getString("currency"),
                it.getString("direction"),
                it.getString("rail"),
                it.getOffsetDateTime("bucket_start").toInstant(),
                it.getString("band_code"),
                it.getLong("movement_count"),
            )
        }.toSet()
    }

    private fun count(sql: String, id: UUID): Long = onEventLoop {
        pool.preparedQuery(sql).execute(Tuple.of(id)).awaitSuspending().iterator().next().getLong(0)
    }

    private fun movements(account: UUID) =
        count("SELECT count(*) FROM aml_booked_movement WHERE account_id = $1", account)

    private fun counted(account: UUID) =
        count("SELECT count(*) FROM aml_booked_movement WHERE account_id = $1 AND counted_at IS NOT NULL", account)

    private fun origins(transaction: UUID) =
        count("SELECT count(*) FROM aml_movement_origin WHERE transaction_id = $1", transaction)

    private fun await(what: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(ready()).describedAs(what).isTrue()
    }

    /** The shape `AccountBookedChangedEvent` serialises to, `delta` as a JSON number. */
    private fun booked(
        journal: UUID,
        account: UUID,
        currency: String,
        delta: String,
        transaction: UUID,
        at: String,
    ): String = mapper.writeValueAsString(
        mapOf(
            "eventId" to UUID.randomUUID().toString(),
            "aggregateId" to account.toString(),
            "aggregateType" to "Account",
            "eventType" to "AccountBookedChanged",
            "version" to 1,
            "currency" to currency,
            "delta" to BigDecimal(delta),
            "journalEntryId" to journal.toString(),
            "transactionId" to transaction.toString(),
            "entryDate" to at.substring(0, DATE_LENGTH),
            "occurredAt" to at,
            "sourceService" to "ledger-service",
        ),
    )

    private fun initiated(transaction: UUID, rail: String): String = mapper.writeValueAsString(
        mapOf(
            "eventId" to UUID.randomUUID().toString(),
            "aggregateId" to transaction.toString(),
            "eventType" to "TransactionInitiated",
            "rail" to rail,
            "amount" to BigDecimal("1.00"),
            "currencyCode" to "CZK",
            "sourceService" to "transaction-service",
        ),
    )

    private fun connection(): Map<String, Any> =
        mapOf("bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java))

    private fun send(topic: String, key: String, vararg payloads: String) {
        KafkaProducer<String, String>(
            connection() + mapOf("key.serializer" to SERIALIZER, "value.serializer" to SERIALIZER, "acks" to "all"),
        ).use { producer ->
            payloads.forEach {
                producer.send(ProducerRecord(topic, key, it)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    private fun id(suffix: String): UUID = UUID.fromString("00000000-0000-4000-8000-$suffix")

    @Test
    fun `a series of booked movements increments exactly the right counters and never mixes currencies`() {
        val domestic = (1..4).map { id("0000000d000$it") }
        val sepa = id("0000000e0001")
        val noOrigin = id("0000000f0001")
        send(
            TRANSACTIONS,
            "series",
            *(
                domestic.map {
                    initiated(it, "DOMESTIC")
                } + initiated(sepa, "SEPA_CT")
                ).toTypedArray(),
        )
        await("the originating events are recorded") { (domestic + sepa).all { origins(it) == 1L } }

        send(
            LEDGER,
            SERIES_ACCOUNT.toString(),
            booked(id("00000001a001"), SERIES_ACCOUNT, "CZK", "-950.00", domestic[0], "2026-03-01T10:05:00Z"),
            booked(id("00000001a002"), SERIES_ACCOUNT, "CZK", "-999.99", domestic[1], "2026-03-01T10:40:00Z"),
            // Exactly the band's upper bound: exclusive, so it counts in ALL only.
            booked(id("00000001a003"), SERIES_ACCOUNT, "CZK", "-1000.00", domestic[2], "2026-03-01T10:59:59Z"),
            // One second later is the next bucket.
            booked(id("00000001a004"), SERIES_ACCOUNT, "CZK", "-920.00", domestic[3], "2026-03-01T11:00:00Z"),
            // 95 EUR: its own currency, its own rail, its own band.
            booked(id("00000001a005"), SERIES_ACCOUNT, "EUR", "-95.00", sepa, "2026-03-01T10:10:00Z"),
            // Money in, posted by no transaction (interest, a fee refund): counted without a rail.
            booked(id("00000001a006"), SERIES_ACCOUNT, "CZK", "950.00", noOrigin, "2026-03-01T10:20:00Z"),
        )
        await("all six movements are counted by the scheduled sweep") { counted(SERIES_ACCOUNT) == 6L }

        val ten = Instant.parse("2026-03-01T10:00:00Z")
        val eleven = Instant.parse("2026-03-01T11:00:00Z")
        assertThat(counters(SERIES_PARTY)).containsExactlyInAnyOrder(
            Counter("CZK", "OUT", "DOMESTIC", ten, "ALL", 3),
            Counter("CZK", "OUT", "DOMESTIC", ten, "SYN_CZK_BELOW_MARK", 2),
            Counter("CZK", "OUT", "DOMESTIC", eleven, "ALL", 1),
            Counter("CZK", "OUT", "DOMESTIC", eleven, "SYN_CZK_BELOW_MARK", 1),
            Counter("EUR", "OUT", "SEPA_CT", ten, "ALL", 1),
            Counter("EUR", "OUT", "SEPA_CT", ten, "SYN_EUR_BELOW_MARK", 1),
            Counter("CZK", "IN", "NONE", ten, "ALL", 1),
            Counter("CZK", "IN", "NONE", ten, "SYN_CZK_BELOW_MARK", 1),
        )

        // The read port (what a pack evaluation and a later admission step ask): one currency, one
        // band, one window. Three questions whose answers differ only by what they must NOT include.
        fun read(currency: String, band: String, to: Instant, direction: MovementDirection? = null) = onEventLoop {
            reader.countMovements(MovementCountQuery(SERIES_PARTY, currency, band, 1, ten, to, direction))
        }
        assertThat(read("CZK", "SYN_CZK_BELOW_MARK", eleven, MovementDirection.OUT))
            .describedAs("the 11:00 bucket is outside a window that ends at 11:00").isEqualTo(2L)
        assertThat(read("CZK", "SYN_CZK_BELOW_MARK", eleven.plusSeconds(1), MovementDirection.OUT)).isEqualTo(3L)
        assertThat(read("CZK", "ALL", eleven.plusSeconds(1))).describedAs("both directions, CZK only").isEqualTo(5L)
        assertThat(read("EUR", "ALL", eleven.plusSeconds(1))).describedAs("EUR is never added to CZK").isEqualTo(1L)
        assertThat(read("USD", "ALL", eleven.plusSeconds(1))).isZero()
    }

    @Test
    fun `a replayed booking and a contradicting origin change nothing`() {
        val transaction = id("0000000d0101")
        val journal = id("00000002a001")
        send(TRANSACTIONS, "replay", initiated(transaction, "DOMESTIC"))
        await("origin recorded") { origins(transaction) == 1L }
        send(LEDGER, REPLAY_ACCOUNT.toString(), booked(journal, REPLAY_ACCOUNT, "CZK", "-950.00", transaction, AT))
        await("first delivery counted") { counted(REPLAY_ACCOUNT) == 1L }
        val before = counters(REPLAY_PARTY)
        assertThat(before).containsExactlyInAnyOrder(
            Counter("CZK", "OUT", "DOMESTIC", Instant.parse("2026-03-02T09:00:00Z"), "ALL", 1),
            Counter("CZK", "OUT", "DOMESTIC", Instant.parse("2026-03-02T09:00:00Z"), "SYN_CZK_BELOW_MARK", 1),
        )

        // booked() draws a fresh eventId each call: the same booking under new transport ids, twice.
        // Same partition key as the sentinel, so the sentinel being counted proves these were consumed.
        send(TRANSACTIONS, "replay", initiated(transaction, "SWIFT"))
        send(
            LEDGER,
            REPLAY_ACCOUNT.toString(),
            booked(journal, REPLAY_ACCOUNT, "CZK", "-950.00", transaction, AT),
            booked(journal, REPLAY_ACCOUNT, "CZK", "-950.00", transaction, AT),
            booked(id("00000002a009"), SENTINEL_ACCOUNT, "CZK", "-1.00", id("0000000f0102"), AT),
        )
        await("the sentinel behind the replays is counted") { counted(SENTINEL_ACCOUNT) == 1L }

        assertThat(movements(REPLAY_ACCOUNT)).describedAs("one booking, one row").isEqualTo(1L)
        assertThat(counters(REPLAY_PARTY)).describedAs("a replay must not move a counter").isEqualTo(before)
    }

    @Test
    fun `a poison record is dead-lettered and the channel keeps consuming`() {
        val poison = listOf("{not json", """{"eventType":"AccountBookedChanged","aggregateId":"$POISON_ACCOUNT"}""")
        val badOrigin = """{"eventType":"TransactionInitiated","aggregateId":"not-a-uuid"}"""
        KafkaConsumer<String, String>(
            connection() + mapOf(
                "group.id" to "aml-intake-dlq-proof",
                "auto.offset.reset" to "earliest",
                "key.deserializer" to DESERIALIZER,
                "value.deserializer" to DESERIALIZER,
            ),
        ).use { deadLetters ->
            deadLetters.subscribe(listOf(LEDGER_DLQ, TRANSACTIONS_DLQ))
            send(TRANSACTIONS, "poison", badOrigin)
            send(
                LEDGER,
                POISON_ACCOUNT.toString(),
                *(
                    poison + booked(
                        id("00000003a001"),
                        POISON_ACCOUNT,
                        "CZK",
                        "-5.00",
                        id("0000000f0201"),
                        AT,
                    )
                    ).toTypedArray(),
            )
            val received = mutableMapOf<String, MutableList<String>>()
            val deadline = System.nanoTime() + BUDGET_NANOS
            while (received.values.sumOf { it.size } < poison.size + 1 && System.nanoTime() < deadline) {
                deadLetters.poll(Duration.ofMillis(POLL_MILLIS)).forEach { record ->
                    assertThat(record.headers().lastHeader("dead-letter-reason")).isNotNull()
                    received.getOrPut(record.topic()) { mutableListOf() } += record.value()
                }
            }
            assertThat(received[LEDGER_DLQ]).containsExactlyElementsOf(poison)
            assertThat(received[TRANSACTIONS_DLQ]).containsExactly(badOrigin)
        }
        await("the valid booking behind the poison is still counted") { counted(POISON_ACCOUNT) == 1L }
        assertThat(movements(POISON_ACCOUNT)).describedAs("poison writes no row").isEqualTo(1L)
    }

    @Test
    fun `a movement whose account has no known party stays uncounted and moves no counter`() {
        send(
            LEDGER,
            ORPHAN_ACCOUNT.toString(),
            booked(id("00000004a001"), ORPHAN_ACCOUNT, "CZK", "-5.00", id("0000000f0301"), AT),
        )
        await("recorded") { movements(ORPHAN_ACCOUNT) == 1L }
        await("the sweep attempted it") {
            count(
                "SELECT count(*) FROM aml_booked_movement WHERE account_id = $1 AND last_attempt_at IS NOT NULL",
                ORPHAN_ACCOUNT,
            ) ==
                1L
        }
        assertThat(counted(ORPHAN_ACCOUNT)).isZero()
        assertThat(count("SELECT count(*) FROM aml_account_party WHERE account_id = $1", ORPHAN_ACCOUNT)).isZero()
    }

    private companion object {
        const val LEDGER = "openbank.ledger.journal.posted"
        const val TRANSACTIONS = "openbank.transactions.transaction.initiated"
        const val LEDGER_DLQ = "openbank.dlq.aml.ledger-events-in"
        const val TRANSACTIONS_DLQ = "openbank.dlq.aml.transaction-events-in"
        const val SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer"
        const val DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer"
        const val AT = "2026-03-02T09:30:00Z"
        const val DATE_LENGTH = 10
        const val BUDGET_NANOS = 90_000_000_000L
        const val POLL_MILLIS = 250L
        const val SEND_TIMEOUT_SECONDS = 20L

        val SERIES_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000ac01")
        val SERIES_PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000bb01")
        val REPLAY_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000ac02")
        val REPLAY_PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000bb02")
        val SENTINEL_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000ac03")
        val POISON_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000ac04")
        val ORPHAN_ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000ac05")
        val OWNERS: Map<UUID, UUID> = mapOf(
            SERIES_ACCOUNT to SERIES_PARTY,
            REPLAY_ACCOUNT to REPLAY_PARTY,
            SENTINEL_ACCOUNT to UUID.fromString("00000000-0000-4000-8000-00000000bb03"),
            POISON_ACCOUNT to UUID.fromString("00000000-0000-4000-8000-00000000bb04"),
        )
    }
}
