// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.persistence.repository.JournalHistoryManifest
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "receipt-reader", roles = ["ROLE_AUDITOR"])
class JournalHistoryCaptureReceiptIT {
    @Inject lateinit var dataSource: AgroalDataSource
    private val account = UUID.randomUUID()
    private val journals = mutableListOf<UUID>()
    private val body get() = mapOf(
        "accountId" to account,
        "currency" to "CZK",
        "fromDate" to "2026-10-01",
        "throughDate" to "2026-10-31",
    )

    @AfterEach
    fun cleanup() {
        dataSource.connection.use { c ->
            journals.forEach { id ->
                c.prepareStatement("DELETE FROM journal_lines WHERE journal_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                c.prepareStatement("DELETE FROM journal_entries WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    @Order(1)
    fun `first authenticated principal creates its own replay binding`() {
        val response = given().contentType("application/json").body(scopeBody())
            .header("Idempotency-Key", scopeKey).post(PATH).then().statusCode(200).extract()
        firstScopeSnapshot = response.jsonPath().getString("snapshotId")
        assertThat(response.header("X-Idempotency-Replayed")).isNull()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "receipt-other-reader", roles = ["ROLE_AUDITOR"])
    fun `another authenticated principal cannot replay the same key and body`() {
        val response = given().contentType("application/json").body(scopeBody())
            .header("Idempotency-Key", scopeKey).post(PATH).then().statusCode(200).extract()
        assertThat(response.header("X-Idempotency-Replayed")).isNull()
        assertThat(response.jsonPath().getString("snapshotId")).isNotEqualTo(firstScopeSnapshot)
    }

    private fun waitUntil(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(50)
        }
        return ready()
    }

    private fun scopeBody() = mapOf(
        "accountId" to "20000000-0000-4000-8000-000000013763",
        "currency" to "CZK",
        "fromDate" to "2026-10-01",
        "throughDate" to "2026-10-31",
    )

    @Test
    fun `source erasure refuses replay and does not recreate its financial manifest`() {
        val source = seed()
        val key = UUID.randomUUID().toString()
        val first = capture(key).then().statusCode(201).extract().jsonPath()
        val id = first.getString("snapshotId")
        deleteSource(source)
        given().get("$PATH/$id").then().statusCode(404)
        capture(key).then().statusCode(410)
        assertThat(snapshotCount()).isZero()
        // Different body is still a key conflict even after its source was erased.
        given().contentType("application/json").body(body + ("throughDate" to "2026-10-30"))
            .header("Idempotency-Key", key).post(PATH).then().statusCode(409)
    }

    @Test
    fun `HTTP failure after database commit recovers the first snapshot without an external save`() {
        seed()
        val key = UUID.randomUUID().toString()
        val failed = given().contentType("application/json").body(body).header("Idempotency-Key", key)
            .header("X-Test-After-Commit-Failure", "true").post(PATH).then().statusCode(500).extract().jsonPath()
        val committed = failed.getString("committedSnapshotId")
        assertThat(snapshotCount()).isEqualTo(1)
        capture(key).then().statusCode(201).header("X-Idempotency-Replayed", "true").extract().jsonPath().also {
            assertThat(it.getString("snapshotId")).isEqualTo(committed)
        }
        assertThat(snapshotCount()).isEqualTo(1)
    }

    @Test
    fun `receipt insert failure rolls back snapshot pages and provenance before retry`() {
        seed()
        val key = UUID.randomUUID().toString()
        val name = "receipt_abort_" + account.toString().replace("-", "").take(12)
        val before = cacheCounts()
        installTrigger(name, "journal_history_capture_receipts", "RAISE EXCEPTION 'synthetic receipt abort';")
        try {
            capture(key).then().statusCode(500)
            assertThat(snapshotCount()).isZero()
            assertThat(cacheCounts()).isEqualTo(before)
        } finally {
            dropTrigger(name, "journal_history_capture_receipts")
        }
        capture(key).then().statusCode(201)
        assertThat(snapshotCount()).isEqualTo(1)
    }

    @Test
    fun `concurrent first claims commit only one source snapshot`() {
        seed()
        val key = UUID.randomUUID().toString()
        val name = "receipt_barrier_" + account.toString().replace("-", "").take(12)
        val lock = (account.leastSignificantBits and 0x7fffffffL) + 1
        installTrigger(name, "journal_history_snapshots", "PERFORM pg_advisory_xact_lock($lock);")
        val executor = Executors.newSingleThreadExecutor()
        dataSource.connection.use { control ->
            control.autoCommit = false
            control.createStatement().use { it.execute("SELECT pg_advisory_xact_lock($lock)") }
            val first = executor.submit<Response> { capture(key) }
            try {
                assertThat(
                    waitUntil {
                        control.createStatement().use { statement ->
                            statement.executeQuery(
                                "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND objid=$lock",
                            ).use { rows ->
                                rows.next()
                                rows.getInt(1) > 0
                            }
                        }
                    },
                ).describedAs("first capture reaches the PostgreSQL barrier").isTrue()
                capture(key).then().statusCode(409)
                control.rollback()
                val id = first.get(
                    20,
                    TimeUnit.SECONDS,
                ).then().statusCode(201).extract().jsonPath().getString("snapshotId")
                capture(key).then().statusCode(201).header("X-Idempotency-Replayed", "true").extract().jsonPath().also {
                    assertThat(it.getString("snapshotId")).isEqualTo(id)
                }
                assertThat(snapshotCount()).isEqualTo(1)
            } finally {
                control.rollback()
                executor.shutdownNow()
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
                dropTrigger(name, "journal_history_snapshots")
            }
        }
    }

    @Test
    fun `empty replay keeps original view and expiry without retaining a financial response`() {
        val key = UUID.randomUUID().toString()
        val first = capture(key).then().statusCode(200).extract().jsonPath()
        val id = UUID.fromString(first.getString("snapshotId"))
        val expiry = emptyExpiry(id)
        val replay = capture(key).then().statusCode(200).header("X-Idempotency-Replayed", "true").extract().jsonPath()
        assertThat(replay.getString("snapshotId")).isEqualTo(id.toString())
        assertThat(replay.getString("observedAt")).isEqualTo(first.getString("observedAt"))
        assertThat(replay.getString("databaseView")).isEqualTo(first.getString("databaseView"))
        assertThat(emptyExpiry(id)).isEqualTo(expiry)
        assertThat(snapshotCount()).isZero()
    }

    @Test
    fun `expired binding allows a fresh result at the original fixed lifetime boundary`() {
        val key = UUID.randomUUID().toString()
        val original = capture(key).then().statusCode(200).extract().jsonPath().getString("snapshotId")
        // Insert a historical completed receipt as a fixture; never UPDATE the immutable binding
        // or alter the production clock. Its expires_at is exactly this SQL statement's instant.
        dataSource.connection.use { c ->
            c.prepareStatement(
                """WITH old AS (DELETE FROM journal_history_capture_receipts WHERE empty_result_id = ? RETURNING *)
                   INSERT INTO journal_history_capture_receipts
                   (scope_key_hash,request_hash,result_kind,empty_result_id,empty_observed_at,empty_database_view,created_at,expires_at)
                   SELECT scope_key_hash,request_hash,result_kind,empty_result_id,empty_observed_at,empty_database_view,
                       statement_timestamp()-interval '86400 seconds',statement_timestamp() FROM old""",
            ).use {
                it.setObject(1, UUID.fromString(original))
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        val fresh = capture(key).then().statusCode(200).extract().jsonPath()
        assertThat(fresh.getString("snapshotId")).isNotEqualTo(original)
    }

    private fun cacheCounts(): List<Long> = dataSource.connection.use { c ->
        listOf(
            "journal_history_snapshots",
            "journal_history_snapshot_entries",
            "journal_history_snapshot_sources",
            "journal_history_capture_receipts",
        ).map { table ->
            c.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
    }

    private fun capture(key: String): Response = given().contentType("application/json").body(body)
        .header("Idempotency-Key", key).post(PATH)

    private fun emptyExpiry(id: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT expires_at::text FROM journal_history_capture_receipts WHERE empty_result_id = ?",
        ).use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getString(1)
            }
        }
    }

    private fun snapshotCount(): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM journal_history_snapshots WHERE account_id = ?").use {
            it.setObject(1, account)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun seed(): UUID = dataSource.connection.use { c ->
        val id = UUID.randomUUID()
        journals += id
        c.prepareStatement(
            "INSERT INTO journal_entries(id,transaction_id,entry_date,value_date,status,created_by) " +
                "VALUES (?,?,'2026-10-01','2026-10-01','POSTED',?)",
        ).use {
            it.setObject(1, id)
            it.setObject(2, UUID.randomUUID())
            it.setObject(3, UUID.randomUUID())
            it.executeUpdate()
        }
        listOf("C" to account, "D" to null).forEachIndexed { index, (side, customer) ->
            c.prepareStatement(
                "INSERT INTO journal_lines(id,journal_id,gl_account_id,side,amount,currency_code," +
                    "base_amount,base_currency,sequence,sub_account_id) VALUES (?,?,?,?,10,'CZK',10,'CZK',?,?)",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, id)
                it.setObject(3, UUID.fromString(if (customer == null) GL_OFFSET else GL_CUSTOMER))
                it.setString(4, side)
                it.setInt(5, index + 1)
                it.setObject(6, customer)
                it.executeUpdate()
            }
        }
        id
    }

    private fun deleteSource(id: UUID) = dataSource.connection.use { c ->
        c.prepareStatement("DELETE FROM journal_lines WHERE journal_id = ?").use {
            it.setObject(1, id)
            it.executeUpdate()
        }
        c.prepareStatement("DELETE FROM journal_entries WHERE id = ?").use {
            it.setObject(1, id)
            it.executeUpdate()
        }
    }

    private fun installTrigger(name: String, table: String, statement: String) = dataSource.connection.use { c ->
        c.createStatement().use {
            it.execute(
                "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN $statement RETURN NEW; END; \$\$",
            )
            it.execute("CREATE TRIGGER $name BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION $name()")
        }
    }

    private fun dropTrigger(name: String, table: String) = dataSource.connection.use { c ->
        c.createStatement().use {
            it.execute("DROP TRIGGER IF EXISTS $name ON $table")
            it.execute("DROP FUNCTION IF EXISTS $name()")
        }
    }

    private companion object {
        val scopeKey = UUID.randomUUID().toString()
        lateinit var firstScopeSnapshot: String
        const val PATH = "/api/v1/journals/history-snapshots"
        const val GL_CUSTOMER = "a0000000-0000-0000-0000-000000000002"
        const val GL_OFFSET = "a0000000-0000-0000-0000-000000000001"
    }
}

/** Test-only response failure after the resource transaction has completed, not a production hook. */
@Provider
class HistoryReceiptAfterCommitFailure : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (request.getHeaderString("X-Test-After-Commit-Failure") == "true" &&
            response.status == 201 &&
            response.entity is JournalHistoryManifest
        ) {
            val manifest = response.entity as JournalHistoryManifest
            response.status = 500
            response.entity = mapOf("committedSnapshotId" to manifest.snapshotId)
        }
    }
}
