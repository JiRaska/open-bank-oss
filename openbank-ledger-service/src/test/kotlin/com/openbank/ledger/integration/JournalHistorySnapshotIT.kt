// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.ledger.infrastructure.partition.JournalPartitionMaintainer
import com.openbank.ledger.infrastructure.persistence.repository.JournalHistorySnapshotSql
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real HTTP/Postgres wire contract. Snapshot data is deliberately raw, never classified economic truth. */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "snapshot-reader", roles = ["ROLE_AUDITOR"])
class JournalHistorySnapshotIT {
    @Inject
    private lateinit var maintainer: JournalPartitionMaintainer

    private val account = UUID.randomUUID()
    private val gl = UUID.randomUUID()
    private val journalIds = mutableListOf<UUID>()
    private val mapper = jacksonObjectMapper()
    private val partitionName = "journal_entries_history_contract_" + account.toString().replace("-", "").take(12)

    @AfterEach
    fun cleanup() {
        connection().use { c ->
            journalIds.forEach { id ->
                c.prepareStatement("delete from journal_lines where journal_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                c.prepareStatement("delete from ledger_idempotency where journal_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                c.prepareStatement("delete from journal_entries where id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
            c.prepareStatement("delete from gl_accounts where id = ?").use {
                it.setObject(1, gl)
                it.executeUpdate()
            }
            c.createStatement().use { it.executeUpdate("drop table if exists $partitionName") }
        }
    }

    @Test
    fun `wire snapshot freezes committed rows and a complete stable ordinal page set`() {
        connection().use { c ->
            seedGl(c)
            seed(c, LocalDate.parse("2026-09-30"), "C", "100") // opening, not a window event
            seed(c, LocalDate.parse("2026-10-01"), "D", "10")
            seed(c, LocalDate.parse("2026-10-02"), "C", "20")
            seed(c, LocalDate.parse("2026-10-03"), "C", "999", "PENDING")
        }
        connection().use { writer ->
            writer.autoCommit = false
            seed(writer, LocalDate.parse("2026-10-04"), "C", "30")
            val old = capture()
            assertThat(old.getInt("entryCount")).isEqualTo(2)
            assertThat(BigDecimal(old.getString("openingBooked"))).isEqualByComparingTo("100")
            assertThat(BigDecimal(old.getString("closingBooked"))).isEqualByComparingTo("110")
            assertThat(old.getBoolean("accountLifetimeComplete")).isFalse()
            assertThat(old.getBoolean("classifiedHistoryComplete")).isFalse()
            assertThat(old.getString("originTrust")).isEqualTo("AUTHENTICATED_CALLER_ASSERTION_UNVERIFIED")
            writer.commit()
            val id = old.getString("snapshotId")
            val first = page(id, 0, 1)
            val second = page(id, 1, 1)
            assertThat(first.getLong("nextAfterOrdinal")).isEqualTo(1L)
            assertThat(second.get<Any?>("nextAfterOrdinal")).isNull()
            val records =
                listOf(first.getString("records[0].canonicalJson"), second.getString("records[0].canonicalJson"))
            assertThat(records.map { mapper.readTree(it)["origin"].isNull }).containsOnly(true)
            val digest = MessageDigest.getInstance("SHA-256").digest(records.joinToString("\n").toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertThat(digest).isEqualTo(old.getString("recordsSha256"))
            // The newer commit is observable only in a fresh snapshot, not later pages of the old one.
            assertThat(capture().getInt("entryCount")).isEqualTo(3)
            given().get("$PATH/$id").then().statusCode(200).extract().jsonPath().also {
                assertThat(it.getInt("entryCount")).isEqualTo(2)
                assertThat(it.getString("recordsSha256")).isEqualTo(digest)
            }
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `optional raw origin keeps authenticated submitter and cannot assert classified coverage`() {
        val controls = connection().use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery("select id,code from gl_accounts where code in ('2100','1100')").use { rs ->
                    buildMap<String, UUID> { while (rs.next()) put(rs.getString(2), rs.getObject(1, UUID::class.java)) }
                }
            }
        }
        val transaction = UUID.randomUUID()
        val response = given().contentType("application/json").body(
            mapOf(
                "idempotencyKey" to "history-origin-$transaction",
                "transactionId" to transaction,
                "entryDate" to "2026-10-01",
                "valueDate" to "2026-10-01",
                "createdBy" to "00000000-0000-0000-0000-000000000099",
                "origin" to mapOf(
                    "kind" to "PAYMENT_TRANSACTION",
                    "sourceRecordId" to transaction,
                    "rail" to "CARD",
                    "authenticatedSubmitter" to "forged-caller-must-not-be-stored",
                ),
                "lines" to listOf(
                    mapOf(
                        "glAccountId" to controls.getValue("2100"),
                        "side" to "DEBIT",
                        "amount" to "10.00",
                        "currencyCode" to "CZK",
                        "baseAmount" to "10.00",
                        "baseCurrencyCode" to "CZK",
                        "subAccountId" to account,
                    ),
                    mapOf(
                        "glAccountId" to controls.getValue("1100"),
                        "side" to "CREDIT",
                        "amount" to "10.00",
                        "currencyCode" to "CZK",
                        "baseAmount" to "10.00",
                        "baseCurrencyCode" to "CZK",
                    ),
                ),
            ),
        ).post("/api/v1/journals").then().statusCode(201).extract().jsonPath()
        journalIds += UUID.fromString(response.getString("id"))
        val manifest = capture()
        val record = mapper.readTree(
            page(manifest.getString("snapshotId"), 0, 100).getString("records[0].canonicalJson"),
        )
        assertThat(record["origin"]["authenticatedSubmitter"].asText())
            .isEqualTo("00000000-0000-0000-0000-000000000099")
        assertThat(record["origin"]["sourceRecordId"].asText()).isEqualTo(transaction.toString())
        assertThat(manifest.getBoolean("classifiedHistoryComplete")).isFalse()
    }

    @Test
    @TestSecurity(user = "unprivileged-fixture", roles = ["ROLE_CUSTOMER"])
    fun `ordinary customer cannot create or inspect a ledger snapshot`() {
        given().contentType("application/json").body(
            mapOf(
                "accountId" to account,
                "currency" to "CZK",
                "fromDate" to "2026-10-01",
                "throughDate" to "2026-10-31",
            ),
        ).header("Idempotency-Key", UUID.randomUUID().toString()).post(PATH).then().statusCode(403)
        given().get("$PATH/${UUID.randomUUID()}").then().statusCode(403)
    }

    @Test
    fun `true empty attached ledger view is inline and retains no orphan raw cache`() {
        val manifest = capture(200)
        assertThat(manifest.getBoolean("persisted")).isFalse()
        assertThat(manifest.getInt("entryCount")).isZero()
        assertThat(manifest.getBoolean("classifiedHistoryComplete")).isFalse()
        val id = manifest.getString("snapshotId")
        given().get("$PATH/$id").then().statusCode(404)
        connection().use { c ->
            c.prepareStatement("select count(*) from journal_history_snapshots where id = ?").use {
                it.setObject(1, UUID.fromString(id))
                it.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `source erasure removes manifest and raw copies while snapshot updates are refused`() {
        val source = connection().use { c ->
            seedGl(c)
            seed(c, LocalDate.parse("2026-10-01"), "C", "10")
        }
        val id = UUID.fromString(capture().getString("snapshotId"))
        connection().use { c ->
            assertThatThrownBy {
                c.prepareStatement(
                    "update journal_history_snapshot_entries set canonical_json = '{}' where snapshot_id = ?",
                ).use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }.isInstanceOf(SQLException::class.java)
            c.prepareStatement("delete from journal_lines where journal_id = ?").use {
                it.setObject(1, source)
                it.executeUpdate()
            }
            c.prepareStatement("select count(*) from journal_history_snapshot_entries where snapshot_id = ?").use {
                it.setObject(1, id)
                it.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isZero()
                }
            }
        }
        given().get("$PATH/$id").then().statusCode(404)
    }

    @Test
    fun `parent journal erasure also removes all derived rows`() {
        val source = connection().use { c ->
            seedGl(c)
            seed(c, LocalDate.parse("2026-10-01"), "C", "10")
        }
        val id = UUID.fromString(capture().getString("snapshotId"))
        assertCacheRows(id, 1)
        connection().use { c ->
            c.prepareStatement("delete from journal_entries where id = ?").use {
                it.setObject(1, source)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        assertCacheRows(id, 0)
        given().get("$PATH/$id").then().statusCode(404)
    }

    @Test
    fun `erasing source origin metadata removes the old attributed copy`() {
        val source = connection().use { c ->
            seedGl(c)
            seed(c, LocalDate.parse("2026-10-01"), "C", "10").also { id ->
                c.prepareStatement(
                    "update journal_entries set origin_kind='OTHER',origin_record_id=?,origin_submitter='synthetic-actor' where id=?",
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, id)
                    it.executeUpdate()
                }
            }
        }
        val id = UUID.fromString(capture().getString("snapshotId"))
        assertCacheRows(id, 1)
        connection().use { c ->
            c.prepareStatement(
                "update journal_entries set origin_kind=null,origin_record_id=null,origin_submitter=null where id=?",
            ).use {
                it.setObject(1, source)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        assertCacheRows(id, 0)
        given().get("$PATH/$id").then().statusCode(404)
        val fresh = capture()
        assertThat(fresh.getInt("missingOriginCount")).isEqualTo(1)
    }

    @Test
    fun `concurrent committed source erase waits for capture then removes the derived copy`() {
        concurrentErase(rollback = false)
    }

    @Test
    fun `concurrent source erase rollback preserves both source and captured view`() {
        concurrentErase(rollback = true)
    }

    @Test
    fun `failure while copying provenance rolls back manifest and raw records together`() {
        val source = connection().use { c ->
            seedGl(c)
            seed(c, LocalDate.parse("2026-10-01"), "C", "10")
        }
        val name = "history_abort_" + account.toString().replace("-", "").take(12)
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "create function $name() returns trigger language plpgsql as \$\$ begin if NEW.journal_id='$source'::uuid then raise exception 'synthetic provenance abort'; end if; return NEW; end; \$\$",
                )
                it.executeUpdate(
                    "create trigger $name before insert on journal_history_snapshot_sources for each row execute function $name()",
                )
            }
        }
        try {
            capture(500)
            assertThat(rowCount("journal_history_snapshots", "account_id", account)).isZero()
            assertThat(rowCount("journal_history_snapshot_sources", "journal_id", source)).isZero()
        } finally {
            dropTestTrigger(name, "journal_history_snapshot_sources")
        }
    }

    @Test
    fun `actual source partition detach rejects reads and purges retained raw copies`() {
        val partition = partitionName
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "create table $partition partition of journal_entries for values from ('2190-01-01') to ('2191-01-01')",
                )
            }
            seedGl(c)
            seed(c, LocalDate.parse("2190-10-01"), "C", "10")
        }
        try {
            val manifest = given().contentType("application/json").body(
                mapOf(
                    "accountId" to account,
                    "currency" to "CZK",
                    "fromDate" to "2190-10-01",
                    "throughDate" to "2190-10-31",
                ),
            ).header(
                "Idempotency-Key",
                UUID.randomUUID().toString(),
            ).post(PATH).then().statusCode(201).extract().jsonPath()
            val id = manifest.getString("snapshotId")
            connection().use { c ->
                c.createStatement().use { it.executeUpdate("alter table journal_entries detach partition $partition") }
            }
            given().get("$PATH/$id").then().statusCode(404)
            given().get("$PATH/$id/entries").then().statusCode(404)
            // GET rejection above must not be mistaken for physical cleanup. Exercise the injected
            // production scheduler, its real ClusterLock, executor and repository on a Vert.x context.
            assertCacheRows(UUID.fromString(id), 1)
            VertxContextSupport.subscribeAndAwait {
                uni(CoroutineScope(Dispatchers.Unconfined)) { maintainer.maintain() }
            }
            assertCacheRows(UUID.fromString(id), 0)
        } finally {
            reattachPartition(partition)
        }
    }

    private fun reattachPartition(partition: String) {
        connection().use { c ->
            c.createStatement().use {
                val attached = it.executeQuery(
                    "select exists(select 1 from pg_inherits where inhrelid = '$partition'::regclass)",
                ).use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
                if (!attached) {
                    it.executeUpdate(
                        "alter table journal_entries attach partition $partition for values from ('2190-01-01') to ('2191-01-01')",
                    )
                }
            }
        }
    }

    @Test
    fun `missing source invalidation purge uses exact production predicate`() {
        connection().use { c ->
            seedGl(c)
            seed(c, LocalDate.parse("2026-10-01"), "C", "10")
        }
        val id = UUID.fromString(capture().getString("snapshotId"))
        connection().use { c ->
            // A source provenance link no longer present in the attached view has the same predicate
            // after partition DETACH. It must invalidate the WHOLE snapshot, not silently shorten pages.
            c.prepareStatement(
                "insert into journal_history_snapshot_sources(snapshot_id,journal_id,entry_date) values (?,?,?)",
            ).use {
                it.setObject(1, id)
                it.setObject(2, UUID.randomUUID())
                it.setDate(3, Date.valueOf("2026-10-01"))
                it.executeUpdate()
            }
            c.createStatement().use {
                assertThat(it.executeUpdate(JournalHistorySnapshotSql.PURGE_UNAVAILABLE)).isGreaterThanOrEqualTo(1)
            }
        }
        given().get("$PATH/$id").then().statusCode(404)
    }

    @Test
    fun `concurrent source line deletion cannot leave an erased copy`() {
        concurrentErase(false, "delete from journal_lines where journal_id=? and sub_account_id is not null")
    }

    @Test
    fun `concurrent source line update cannot leave an old copy`() {
        concurrentErase(
            false,
            "update journal_lines set amount=11,base_amount=11 where journal_id=? and sub_account_id is not null",
        )
    }

    @Test
    fun `concurrent origin erasure cannot leave an attributed copy`() {
        concurrentErase(
            false,
            "update journal_entries set origin_kind=null,origin_record_id=null,origin_submitter=null where id=?",
        )
    }

    private fun concurrentErase(rollback: Boolean, mutation: String = "delete from journal_entries where id=?") {
        val source = seedConcurrentSource(mutation)
        val name = "history_barrier_" + account.toString().replace("-", "").take(12)
        val lockKey = (account.leastSignificantBits and 0x7fffffffL) + 1
        installCaptureBarrier(name, lockKey)
        val workers = Executors.newFixedThreadPool(2)
        connection().use { control ->
            control.autoCommit = false
            control.createStatement().use { it.execute("select pg_advisory_xact_lock($lockKey)") }
            val captureFuture = workers.submit<JsonPath> { capture() }
            try {
                val capturePid = awaitCapturePid(control, lockKey)
                val eraserPid = CompletableFuture<Int>()
                val eraseFuture = workers.submit { mutateSource(source, mutation, rollback, eraserPid) }
                val erasingPid = eraserPid.get(10, TimeUnit.SECONDS)
                // Either real serialization or committed mutation; neither permits stale retained data.
                awaitDatabaseState {
                    if (eraseFuture.isDone) {
                        eraseFuture.get()
                        true
                    } else {
                        blocks(control, capturePid, erasingPid)
                    }
                }
                control.rollback()
                val manifest = captureFuture.get(20, TimeUnit.SECONDS)
                eraseFuture.get(20, TimeUnit.SECONDS)
                assertSourceUpdate(source, mutation, rollback)
                val id = UUID.fromString(manifest.getString("snapshotId"))
                assertThat(manifest.getInt("entryCount")).isEqualTo(1)
                assertCacheRows(id, if (rollback) 1 else 0)
                given().get("$PATH/$id").then().statusCode(if (rollback) 200 else 404)
            } finally {
                control.rollback()
                workers.shutdownNow()
                assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
                dropTestTrigger(name, "journal_history_snapshots")
            }
        }
    }

    private fun seedConcurrentSource(mutation: String): UUID = connection().use { c ->
        seedGl(c)
        seed(c, LocalDate.parse("2026-10-01"), "C", "10").also { source ->
            if (mutation.contains("origin_kind")) {
                c.prepareStatement(
                    "update journal_entries set origin_kind='OTHER',origin_record_id=?,origin_submitter='synthetic-actor' where id=?",
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, source)
                    it.executeUpdate()
                }
            }
        }
    }

    private fun installCaptureBarrier(name: String, lockKey: Long) = connection().use { c ->
        c.createStatement().use {
            it.executeUpdate(
                "create function $name() returns trigger language plpgsql as \$\$ begin if NEW.account_id='$account'::uuid then perform pg_advisory_xact_lock($lockKey); end if; return NEW; end; \$\$",
            )
            it.executeUpdate(
                "create trigger $name before insert on journal_history_snapshots for each row execute function $name()",
            )
        }
    }

    private fun awaitCapturePid(control: Connection, lockKey: Long): Int {
        var pid = 0
        awaitDatabaseState {
            control.prepareStatement(
                "select pid from pg_locks where locktype='advisory' and classid=0 and objid=? and not granted",
            ).use {
                it.setLong(1, lockKey)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        pid = rs.getInt(1)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        return pid
    }

    private fun mutateSource(source: UUID, mutation: String, rollback: Boolean, pid: CompletableFuture<Int>) =
        connection().use { c ->
            c.autoCommit = false
            c.createStatement().use { st ->
                st.executeQuery("select pg_backend_pid()").use { rs ->
                    rs.next()
                    pid.complete(rs.getInt(1))
                }
            }
            c.prepareStatement(mutation).use {
                it.queryTimeout = 20
                it.setObject(1, source)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            if (rollback) c.rollback() else c.commit()
        }

    private fun blocks(c: Connection, capturePid: Int, erasingPid: Int): Boolean =
        c.prepareStatement("select ? = any(pg_blocking_pids(?))").use {
            it.setInt(1, capturePid)
            it.setInt(2, erasingPid)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun assertSourceUpdate(source: UUID, mutation: String, rollback: Boolean) {
        if (!mutation.startsWith("update") || rollback) return
        val query = if (mutation.contains("origin_kind")) {
            "select origin_kind is null from journal_entries where id=?"
        } else {
            "select amount=11 from journal_lines where journal_id=? and sub_account_id is not null"
        }
        assertThat(queryBoolean(query, source)).isTrue()
    }

    private fun queryBoolean(query: String, id: UUID): Boolean = connection().use { c ->
        c.prepareStatement(query).use {
            it.setObject(1, id)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    private fun rowCount(table: String, column: String, id: UUID): Int = connection().use { c ->
        c.prepareStatement("select count(*) from $table where $column=?").use {
            it.setObject(1, id)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun awaitDatabaseState(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "expected PostgreSQL lock state was not observed" }
            Thread.sleep(20)
        }
    }

    private fun assertCacheRows(id: UUID, expected: Int) {
        for (table in listOf(
            "journal_history_snapshots",
            "journal_history_snapshot_entries",
            "journal_history_snapshot_sources",
        )) {
            val key = if (table == "journal_history_snapshots") "id" else "snapshot_id"
            assertThat(rowCount(table, key, id)).isEqualTo(expected)
        }
    }

    private fun dropTestTrigger(name: String, table: String) {
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate("drop trigger if exists $name on $table")
                it.executeUpdate("drop function if exists $name()")
            }
        }
    }

    private fun capture(expected: Int = 201): JsonPath = given().contentType("application/json").body(
        mapOf("accountId" to account, "currency" to "CZK", "fromDate" to "2026-10-01", "throughDate" to "2026-10-31"),
    ).header(
        "Idempotency-Key",
        UUID.randomUUID().toString(),
    ).post(PATH).then().statusCode(expected).extract().jsonPath()

    private fun page(id: String, after: Int, limit: Int): JsonPath = given()
        .queryParam("afterOrdinal", after).queryParam("limit", limit)
        .get("$PATH/$id/entries").then().statusCode(200).extract().jsonPath()

    private fun seedGl(c: Connection) {
        c.prepareStatement(
            "insert into gl_accounts(id,code,name,type,currency_code) values (?,?,?,'LIABILITY','CZK')",
        ).use {
            it.setObject(1, gl)
            it.setString(2, "H" + gl.toString().replace("-", "").take(12))
            it.setString(3, "Synthetic history contract fixture")
            it.executeUpdate()
        }
    }

    private fun seed(c: Connection, date: LocalDate, side: String, amount: String, status: String = "POSTED"): UUID {
        val id = UUID.randomUUID()
        journalIds += id
        c.prepareStatement(
            "insert into journal_entries(id,transaction_id,entry_date,value_date,status,created_by) values (?,?,?,?,?,?)",
        ).use {
            it.setObject(1, id)
            it.setObject(2, UUID.randomUUID())
            it.setDate(3, Date.valueOf(date))
            it.setDate(4, Date.valueOf(date))
            it.setString(5, status)
            it.setObject(6, UUID.randomUUID())
            it.executeUpdate()
        }
        listOf(side to account, (if (side == "C") "D" else "C") to null).forEachIndexed { index, (lineSide, customer) ->
            c.prepareStatement(
                "insert into journal_lines(id,journal_id,gl_account_id,side,amount,currency_code,base_amount,base_currency,sequence,sub_account_id) values (?,?,?,?,?,'CZK',?,'CZK',?,?)",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, id)
                it.setObject(
                    3,
                    if (customer !=
                        null
                    ) {
                        UUID.fromString("a0000000-0000-0000-0000-000000000002")
                    } else {
                        gl
                    },
                )
                it.setString(4, lineSide)
                it.setBigDecimal(5, BigDecimal(amount))
                it.setBigDecimal(6, BigDecimal(amount))
                it.setInt(7, index + 1)
                it.setObject(8, customer)
                it.executeUpdate()
            }
        }
        return id
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val PATH = "/api/v1/journals/history-snapshots"
    }
}
