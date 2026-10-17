// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp

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

/**
 * The observable distributed shape of a staff OBO agent session issuance (ADR-0224).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the session row into
 * `agent_session` happened *inside* the issuance request's trace. A session persisted outside
 * the request context (a retry on another thread, a detached session) still answers 201 and
 * hands back a live-looking session, but lands in traces of its own — the OBO grant exists
 * without an observable issuer, which is exactly the row/store/caller disagreement class
 * #3182 and #2955 were filed against.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class McpSessionTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    fun `issuing an agent session keeps the request and its session INSERT in one trace`() {
        Given {
            contentType("application/json")
            body("""{"roleCeiling":["ROLE_OPERATOR"],"purpose":"trace-contract"}""")
        } When {
            post("/api/v1/mcp/sessions")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/mcp/sessions")
            .requiresAttribute("POST /api/v1/mcp/sessions", "http.response.status_code")
            .requiresAttribute("INSERT agent_session", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/mcp/sessions",
                "INSERT agent_session",
            )
            .hasNoErrorSpan()
            .verifiedAs("mcp-session-create")
    }
}
