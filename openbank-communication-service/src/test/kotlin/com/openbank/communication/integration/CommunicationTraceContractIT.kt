// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.integration

import com.openbank.communication.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of the four-eyes publish decision below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the UPDATE that actually flipped
 * `style_version` to PUBLISHED happened *inside* the request's trace. A write that escaped
 * the request context — an un-propagated reactive hop, a fire-and-forget executor — still
 * writes the row and still answers 200, is invisible to every assertion this module already
 * had, and lands in a trace of its own. For a publish gate whose whole value is "a second
 * pair of eyes approved THIS call", an untraced status flip is an unauditable one.
 *
 * Seeding the IN_REVIEW version via JDBC mirrors [CommunicationStyleRestContractIT]'s
 * `seedStyleVersion`: `@TestSecurity` is fixed per method, so a maker different from the
 * calling checker can only be simulated by writing the `maker` column directly.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.communication.it.CommunicationPostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class CommunicationTraceContractIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "checker-trace@openbank.test", roles = ["ROLE_COMMS_APPROVER"])
    fun `publishing a style version keeps the request and its status UPDATE in one trace`() {
        val versionId = seedInReviewVersion(persona = "back-office-written", maker = "editor-other@openbank.test")

        Given {
            contentType("application/json")
        } When {
            post("/api/v2/personas/style-versions/$versionId/publish")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v2/personas/style-versions/{id}/publish")
            .requiresAttribute("POST /api/v2/personas/style-versions/{id}/publish", "http.response.status_code")
            .requiresAttribute("UPDATE style_version", "db.sql.table")
            .requiresSameTrace("POST /api/v2/personas/style-versions/{id}/publish", "UPDATE style_version")
            .hasNoErrorSpan()
            .verifiedAs("communication-publish-style-version")
    }

    private fun seedInReviewVersion(persona: String, maker: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            val personaId = connection.prepareStatement("select id from persona where key = ?").use { ps ->
                ps.setString(1, persona)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
            connection.prepareStatement(
                """insert into style_version
                    (id, persona_id, version, status, tone, formality, form_of_address,
                     preferred_terms, forbidden_terms, signature, maker, created_at, base_published_version)
                    values (?, ?, 1, 'IN_REVIEW', 'formal', 'formal', 'vykani', '{}', '[]', ?, ?, ?, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, personaId)
                statement.setString(3, "S pozdravem, Vase banka")
                statement.setString(4, maker)
                statement.setTimestamp(5, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
        return id
    }
}
