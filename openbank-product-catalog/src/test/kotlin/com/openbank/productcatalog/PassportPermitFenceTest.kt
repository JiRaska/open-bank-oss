// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
class PassportPermitFenceTest {
    @Inject
    lateinit var dataSource: DataSource

    @ParameterizedTest
    @ValueSource(strings = ["OPEN", "ACTIVATE"])
    fun `revocation wins when it owns the grant row first`(operation: String) {
        val request = seed(operation)
        dataSource.connection.use { winner ->
            winner.autoCommit = false
            assertTrue(winner.revoke(request.grant))
            assertNull(race(winner) { contender -> contender.consume(request) })
        }
        dataSource.connection.use { read ->
            assertNull(read.scalar("SELECT consumed_at FROM passport_account_permits WHERE id = ?", request.permit))
            assertEquals(
                true,
                read.scalar("SELECT revoked_at IS NOT NULL FROM passport_grants WHERE id = ?", request.grant),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPEN", "ACTIVATE"])
    fun `consumption wins and only the exact operation replays after revocation`(operation: String) {
        val request = seed(operation)
        val receipt = dataSource.connection.use { winner ->
            winner.autoCommit = false
            val consumed = winner.consume(request)
            assertNotNull(consumed)
            assertTrue(race(winner) { contender -> contender.revoke(request.grant) })
            consumed
        }
        dataSource.connection.use { read ->
            assertEquals(receipt, read.consume(request))
            assertNull(read.consume(request.copy(party = UUID.randomUUID())))
            assertNull(read.consume(request.copy(operationId = UUID.randomUUID())))
            assertNull(read.consume(request.copy(operation = if (operation == "OPEN") "ACTIVATE" else "OPEN")))
            assertEquals(request.permit, read.issue(request.copy(permit = UUID.randomUUID())))
            assertNull(read.issue(request.copy(operationId = UUID.randomUUID(), permit = UUID.randomUUID())))
            assertNull(read.issue(request.copy(country = "AT")))
            assertFalse(read.revoke(request.grant))
        }
    }

    private fun <T> race(winner: Connection, contend: (Connection) -> T): T =
        Executors.newSingleThreadExecutor().use { executor ->
            dataSource.connection.use { contender ->
                val pid = contender.scalar("SELECT pg_backend_pid()") as Int
                val winnerPid = winner.scalar("SELECT pg_backend_pid()") as Int
                contender.autoCommit = false
                contender.createStatement().use { it.execute("SET LOCAL statement_timeout = '10s'") }
                val waiting = executor.submit<T> {
                    val outcome = contend(contender)
                    contender.commit()
                    outcome
                }
                awaitBlocked(waiting, winnerPid, pid)
                winner.commit()
                waiting.get(5, TimeUnit.SECONDS)
            }
        }

    private fun awaitBlocked(waiting: Future<*>, winnerPid: Int, pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        dataSource.connection.use { observer ->
            while (observer.scalar("SELECT ? = ANY(pg_blocking_pids(?))", winnerPid, pid) != true) {
                check(!waiting.isDone && System.nanoTime() < deadline) { "Contender did not wait on the grant" }
                Thread.sleep(10)
            }
        }
    }

    private fun seed(operation: String): PermitRequest {
        val request = PermitRequest(operation)
        dataSource.connection.use { db ->
            val offering = UUID.randomUUID()
            val specification = UUID.randomUUID()
            db.execute(
                """
                INSERT INTO catalog_specifications (id, code, schema_id, schema_version, created_at)
                SELECT ?, ?, schema_id, schema_version, CURRENT_TIMESTAMP FROM catalog_schemas LIMIT 1
                """.trimIndent(),
                specification,
                "SPEC_$specification",
            )
            db.execute(
                """
                INSERT INTO catalog_offerings (id, specification_id, code, market)
                VALUES (?, ?, ?, '{"countries":["SK"]}')
                """.trimIndent(),
                offering,
                specification,
                "OFFER_$offering",
            )
            db.execute(
                """
                INSERT INTO catalog_revisions (id, offering_id, revision_no, schema_id, schema_version,
                    state, content, effective_from, maker_id, checker_id, created_at, updated_at)
                SELECT ?, ?, 1, schema_id, schema_version, 'PUBLISHED', '{}', CURRENT_TIMESTAMP - interval '1 day',
                    'synthetic_maker', 'synthetic_checker', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM catalog_specifications WHERE id = ?
                """.trimIndent(),
                request.revision,
                offering,
                specification,
            )
            db.execute(
                """
                INSERT INTO passport_grants (id, bank_id, establishment_id, host_country, mode, offering_id,
                    channel, customer_segment, pack_set_digest, evidence_ref, proposed_by, approved_by,
                    effective_from, effective_until)
                VALUES (?, ?, ?, 'SK', 'FOS', ?, 'WEB', 'RETAIL', ?, 'synthetic-proof', ?, ?,
                    CURRENT_TIMESTAMP - interval '1 day', CURRENT_TIMESTAMP + interval '1 day')
                """.trimIndent(),
                request.grant,
                request.bank,
                request.establishment,
                offering,
                HASH,
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
            assertEquals(request.permit, db.issue(request))
        }
        return request
    }

    private fun Connection.issue(request: PermitRequest): UUID? = scalar(
        "SELECT issue_passport_account_permit(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.permit,
        request.grant,
        request.operation,
        request.operationId,
        request.party,
        request.revision,
        HASH,
        request.bank,
        request.establishment,
        request.country,
        "FOS",
        "WEB",
        "RETAIL",
        HASH,
    ) as? UUID

    private fun Connection.consume(request: PermitRequest): OffsetDateTime? =
        prepareStatement("SELECT consume_passport_account_permit(?, ?, ?, ?, ?, ?)").use { statement ->
            listOf(
                request.permit,
                request.operation,
                request.operationId,
                request.party,
                request.revision,
                HASH,
            ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                check(result.next())
                result.getObject(1, OffsetDateTime::class.java)
            }
        }

    private fun Connection.revoke(grant: UUID): Boolean =
        scalar("SELECT revoke_passport_grant(?, ?, 'synthetic revoke')", grant, UUID.randomUUID()) as Boolean

    private fun Connection.scalar(sql: String, vararg values: Any): Any? = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            check(result.next())
            result.getObject(1)
        }
    }

    private fun Connection.execute(sql: String, vararg values: Any) = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        assertEquals(1, statement.executeUpdate())
    }

    private data class PermitRequest(
        val operation: String,
        val grant: UUID = UUID.randomUUID(),
        val permit: UUID = UUID.randomUUID(),
        val operationId: UUID = UUID.randomUUID(),
        val party: UUID = UUID.randomUUID(),
        val revision: UUID = UUID.randomUUID(),
        val bank: UUID = UUID.randomUUID(),
        val establishment: UUID = UUID.randomUUID(),
        val country: String = "SK",
    )

    private companion object {
        val HASH = "a".repeat(64)
    }
}
