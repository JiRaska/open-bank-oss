// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.integration

import com.openbank.securityscanner.it.SecurityScannerPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #4728: the DORA ICT incident register must live in a row, not in a per-pod
 * `ConcurrentHashMap`.
 *
 * What makes this test able to see the defect it pins: it asserts through a channel the old code
 * could not have satisfied. A test that reported an incident and then read it back through the same
 * pod's REST API passed perfectly well against the `ConcurrentHashMap` — the map is right there and
 * answers correctly. The read that discriminates is a **plain JDBC SELECT against
 * `ict_incidents`**, on a different connection from the reactive session that wrote it: that row
 * exists only if the state genuinely left the process, and it is what survives the restart the old
 * implementation lost the whole register to.
 *
 * Driven through the real REST endpoints rather than by calling the repository from the test
 * thread: a reactive Panache repository invoked from a bare `@QuarkusTest` thread throws
 * `No current Vertx context found`, because only an HTTP request carries a Vert.x context
 * (see ConsentRevocationOutboxIT / LendingOutboxWriteIT for the same constraint).
 *
 * The second assertion is the one the assigned-id trap costs: `updateStatus` writes the SAME id a
 * second time. With `persist()` that is an unconditional INSERT and the transition 500s on
 * `duplicate key value violates "ict_incidents_pkey"` — invisible to any mocked-repo test, and the
 * exact failure the consent service shipped (#1521). Asserting a 200 plus the changed column is
 * what proves `merge` is doing the upsert.
 */
@QuarkusTest
@QuarkusTestResource(IctIncidentDurabilityIT.InMemoryKafkaResource::class)
@QuarkusTestResource(SecurityScannerPostgresTestResource::class)
class IctIncidentDurabilityIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("ict-incident-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun reportIncident(title: String): UUID {
        val body = """
            {"title":"$title","description":"kafka broker unreachable from the payments namespace",
             "category":"AVAILABILITY","severity":"P2_HIGH","affectedServices":["ledger","transaction"],
             "detectedAt":null,"assignedTo":"soc-oncall"}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/ict-incidents")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        assertThat(id).isNotBlank()
        return UUID.fromString(id)
    }

    /** Reads the register the way a *different pod* would: a fresh JDBC connection, not the map. */
    private fun selectIncident(id: UUID): Map<String, Any?>? = dataSource.connection.use { conn -> queryRow(conn, id) }

    private fun queryRow(conn: Connection, id: UUID): Map<String, Any?>? =
        conn.prepareStatement(SELECT_SQL).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) rowOf(rs) else null }
        }

    private fun rowOf(rs: ResultSet): Map<String, Any?> = mapOf(
        "status" to rs.getString("status"),
        "severity" to rs.getString("severity"),
        "affectedServices" to rs.getString("affected_services"),
        "reportedToRegulator" to rs.getBoolean("reported_to_regulator"),
        "regulatoryReportId" to rs.getString("regulatory_report_id"),
        "containedAt" to rs.getTimestamp("contained_at"),
    )

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a reported incident lands in ict_incidents, not only in the pod's memory`() {
        val id = reportIncident("kafka outage ${UUID.randomUUID()}")

        val row = selectIncident(id)
        assertThat(row)
            .describedAs("the register must survive the process — no row means it is still per-pod state")
            .isNotNull
        assertThat(row!!["status"]).isEqualTo("OPEN")
        assertThat(row["severity"]).isEqualTo("P2_HIGH")
        assertThat(row["affectedServices"]).isEqualTo("ledger,transaction")
        assertThat(row["reportedToRegulator"]).isEqualTo(false)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a status transition updates the same row rather than re-inserting it`() {
        val id = reportIncident("contained incident ${UUID.randomUUID()}")

        Given {
            contentType("application/json")
            body(
                """{"status":"CONTAINED","containedAt":"2026-08-15T10:00:00Z","resolvedAt":null,"rtoMinutes":45,"rpoMinutes":5}""",
            )
        } When {
            patch("/api/v1/ict-incidents/$id/status")
        } Then {
            // 500 here is the assigned-id `persist()` trap: an unconditional INSERT on an id that
            // already exists. A 200 means the repository merged.
            statusCode(200)
        }

        val row = selectIncident(id)
        assertThat(row).isNotNull
        assertThat(row!!["status"]).isEqualTo("CONTAINED")
        assertThat(row["containedAt"]).isNotNull
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `the regulator-reporting flag is durable, not a field that vanishes with the pod`() {
        val id = reportIncident("reportable incident ${UUID.randomUUID()}")

        Given {
            contentType("application/json")
            body("""{"regulatoryReportId":"CNB-DORA-2026-0042"}""")
        } When {
            post("/api/v1/ict-incidents/$id/regulatory-report")
        } Then {
            statusCode(200)
        }

        val row = selectIncident(id)
        assertThat(row).isNotNull
        // Losing this one is the worst case of the old shape: a restart would have reset the
        // register to "nothing was ever reported to the regulator", with no error anywhere.
        assertThat(row!!["reportedToRegulator"]).isEqualTo(true)
        assertThat(row["regulatoryReportId"]).isEqualTo("CNB-DORA-2026-0042")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `the list endpoint reads the table and filters server-side`() {
        val id = reportIncident("listed incident ${UUID.randomUUID()}")

        val ids = Given {
            contentType("application/json")
        } When {
            get("/api/v1/ict-incidents?status=OPEN&severity=P2_HIGH&limit=200")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList<String>("id")
        }
        assertThat(ids).contains(id.toString())

        // A filter that matches nothing must come back empty rather than unfiltered — the filter
        // moved from an in-memory `.filter {}` to a WHERE clause, so it is newly able to be wrong.
        val none = Given {
            contentType("application/json")
        } When {
            get("/api/v1/ict-incidents?severity=P4_LOW")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList<String>("id")
        }
        assertThat(none).doesNotContain(id.toString())
    }

    private companion object {
        const val SELECT_SQL =
            "SELECT status, severity, affected_services, reported_to_regulator, " +
                "regulatory_report_id, contained_at FROM ict_incidents WHERE id = ?"
    }
}
