// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.tppregistry.integration

import com.openbank.tppregistry.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #4007: `tpp_outbox` shipped a dispatcher, a backlog gauge, an atomic-claim query,
 * `openbank.outbox.dispatch-enabled: true`, a `KafkaTppOutboxEventPublisher`, a `KafkaTopic`
 * resource and a write ACL — and **nothing ever wrote a row**. This service was the strictest
 * instance of the pattern: two disjoint package roots (`com.openbank.tpp.*` holds the whole outbox
 * apparatus, `com.openbank.tppregistry.*` holds the registry) with no import crossing between
 * them, and — unlike `party` or `balance` — no second direct emitter either, so nothing has ever
 * been produced to `openbank.tpp.registry.event` at all.
 *
 * Only a real-DB integration test can prove the fix. A unit test that mocks `TppRepository` cannot
 * tell whether the implementation wrote an outbox row — that is exactly why the defect survived a
 * fully green suite for the life of the service. The repository cannot be called directly either:
 * a `Panache.withTransaction` reactive repo invoked from a bare `@QuarkusTest` thread throws
 * "No current Vertx context found"; only a real HTTP request carries a Vert.x context. So this
 * drives the REST endpoints with RestAssured and reads the row back over plain JDBC — the
 * `PartyOutboxWriteIT` / `ConsentRevocationOutboxIT` / `LendingOutboxWriteIT` pattern.
 *
 * The dispatcher is switched off for the duration so it cannot claim a row (DISPATCHING) or mark
 * it SENT before the assertion observes it — the claim under test is that the row is WRITTEN in
 * the state-change transaction, not what happens to it afterwards.
 */
@QuarkusTest
@QuarkusTestResource(TppOutboxWriteIT.DispatcherOffResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class TppOutboxWriteIT {

    class DispatcherOffResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            // The blacklist endpoint carries @Authorize, and an OPA sidecar the interceptor cannot
            // reach fails CLOSED — 503, not 403. Advisory mode here so the test measures the outbox
            // write and not the absence of a PDP container; authorization itself is covered by
            // TppRegistrySecurityTest. Same override as KycOutboxWriteIT.
            "authz.enforce" to "false",
        )
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun registerTpp(tppId: String) {
        val body = """
            {"tppId":"$tppId","name":"Outbox Probe","countryCode":"CZ","nca":"CNB",
             "roles":["AISP"],"qwacSubjectDn":"CN=QWAC","qsealSubjectDn":null}
        """.trimIndent()
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(body)
        } When {
            post("/api/v1/tpp-registry")
        } Then {
            statusCode(201)
        }
    }

    /** The registry's own row, read independently of the outbox — both halves of the guarantee. */
    private fun entryUuid(tppId: String): UUID? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT entry_uuid, status FROM tpp_entries WHERE tpp_id = ?")
        ps.setString(1, tppId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getObject("entry_uuid", UUID::class.java) else null
    }

    private fun entryStatus(tppId: String): String? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT status FROM tpp_entries WHERE tpp_id = ?")
        ps.setString(1, tppId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("status") else null
    }

    // conn.use closes the connection, cascading to its statement/result-set — kept flat to stay
    // within detekt's NestedBlockDepth.
    private fun outboxRows(aggregateId: UUID): List<Triple<String, String, String>> =
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement(
                "SELECT event_type, payload, status FROM tpp_outbox WHERE aggregate_id = ? ORDER BY created_at",
            )
            ps.setObject(1, aggregateId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<Triple<String, String, String>>()
            while (rs.next()) {
                rows += Triple(rs.getString("event_type"), rs.getString("payload"), rs.getString("status"))
            }
            rows
        }

    @Test
    @TestSecurity(user = "outbox-it", roles = ["ROLE_ADMIN"])
    fun `registering a TPP writes TPP_REGISTERED to the outbox in the same transaction as the row`() {
        val tppId = "CZ-CNB-OUTBOX-${UUID.randomUUID().toString().take(8)}"
        registerTpp(tppId)

        val id = entryUuid(tppId)
        assertThat(id).describedAs("tpp_entries.entry_uuid for %s", tppId).isNotNull()

        val rows = outboxRows(id!!)
        assertThat(rows).describedAs("tpp_outbox rows for %s", tppId).hasSize(1)
        val (eventType, payload, status) = rows.single()
        assertThat(eventType).isEqualTo("TPP_REGISTERED")
        assertThat(status).isEqualTo("PENDING")
        // The claim is about the PAYLOAD, not the row's columns: sca-service wrote `eventType` to
        // `sca_outbox.event_type` and not into the body, its test asserted the column, and the
        // consumer's parser silently read "" for 15 SENT events. A consumer of
        // `openbank.tpp.registry.event` sees these bytes and nothing else.
        assertThat(payload).contains("\"eventType\":\"TPP_REGISTERED\"")
        assertThat(payload).contains("\"tppId\":\"$tppId\"")
        assertThat(payload).contains("\"entryId\":\"$id\"")
        assertThat(payload).contains("\"status\":\"ACTIVE\"")
        assertThat(payload).contains("\"roles\":[\"AISP\"]")
        // `occurredAt` (never `timestamp`), rendered with a `Z` offset: audit-service's consumer
        // parses with Instant.parse, which rejects any other offset and silently falls back to
        // ingest time. This is the REAL producer mapper, not a test-local one.
        assertThat(payload).containsPattern("\"occurredAt\":\"[0-9T:.\\-]+Z\"")
    }

    @Test
    @TestSecurity(user = "outbox-it", roles = ["ROLE_ADMIN", "ROLE_OPERATOR"])
    fun `blacklisting a TPP writes TPP_BLACKLISTED alongside the updated row`() {
        val tppId = "CZ-CNB-OUTBOX-${UUID.randomUUID().toString().take(8)}"
        registerTpp(tppId)

        Given {
            contentType("application/json")
            body("""{"reason":"licence revoked by NCA"}""")
        } When {
            post("/api/v1/tpp-registry/$tppId/blacklist")
        } Then {
            statusCode(200)
        }

        // Both halves of the guarantee, asserted against the DB rather than against a mock: the
        // state change landed AND its event landed, in the same commit.
        assertThat(entryStatus(tppId)).describedAs("tpp_entries.status").isEqualTo("BLACKLISTED")

        val id = entryUuid(tppId)!!
        val rows = outboxRows(id)
        assertThat(rows.map { it.first }).containsExactly("TPP_REGISTERED", "TPP_BLACKLISTED")
        val blacklisted = rows.last().second
        assertThat(blacklisted).contains("\"eventType\":\"TPP_BLACKLISTED\"")
        assertThat(blacklisted).contains("\"status\":\"BLACKLISTED\"")
        assertThat(blacklisted).contains("\"blacklistReason\":\"licence revoked by NCA\"")
        assertThat(blacklisted).containsPattern("\"blacklistedAt\":\"[0-9T:.\\-]+Z\"")
    }
}
