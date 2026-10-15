// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.referral.it.ReferralPostgresTestResource
import com.openbank.referral.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of a referral qualification.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the reward row and the
 * INSERTs of its `Qualified` + `RewardRequested` events into `referral_outbox` happened
 * *inside* the qualification request's trace. The ADR-0050 guarantee (aggregate and its domain
 * events commit atomically via the outbox) has relational coverage in `ReferralOutboxWriteIT`;
 * the trace half is what an operations engineer searching Tempo for "where did this reward
 * come from" actually sees. A row/event set that escaped the request context still answers 202
 * and is invisible to every assertion this module already had, but lands in traces of its own —
 * the reward exists without an observable cause.
 */
@QuarkusTest
@QuarkusTestResource(ReferralPostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class ReferralTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Inject
    lateinit var dataSource: DataSource

    /**
     * Seeds a DRAFT program straight into Postgres: publish rejects maker == checker
     * (self-approval), and this test's security context is the checker. Seeding also keeps the
     * prequalification traffic out of the recorder, so `hasNoErrorSpan()` speaks only about the
     * request under contract.
     */
    private fun seedDraftProgram(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into referral_program
                    (id,name,version,reward_amount,currency,qualifying_event,
                     attribution_window_ends_at,status,maker,created_at)
                    values (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "trace-contract-${UUID.randomUUID()}")
                statement.setInt(3, 1)
                statement.setBigDecimal(4, BigDecimal.TEN)
                statement.setString(5, "EUR")
                statement.setString(6, "account.opened")
                statement.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(86_400)))
                statement.setString(8, "DRAFT")
                statement.setString(9, "maker@openbank.test")
                statement.setTimestamp(10, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }

    @Test
    @TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
    fun `qualifying an invite keeps the request, its reward INSERT and its outbox INSERTs in one trace`() {
        val programId = UUID.randomUUID()
        seedDraftProgram(programId)

        When {
            post("/api/v1/referrals/programs/$programId/publish")
        } Then {
            statusCode(200)
        }

        val token = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{"referrerPartyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/programs/$programId/invites")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("token")
        }

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{"refereePartyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/invites/$token/attribute")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{"eventName":"account.opened","eventId":"trace-${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/referrals/invites/$token/qualify")
        } Then {
            statusCode(202)
        }

        // Quarkus' HTTP instrumentation names the span with the route template, so the token
        // never appears in span names and the contract id stays stable across runs.
        val qualifySpan = "POST /api/v1/referrals/invites/{token}/qualify"
        exporter.contract()
            .requiresSpan(qualifySpan)
            .requiresAttribute(qualifySpan, "http.response.status_code")
            .requiresAttribute("INSERT referral_reward", "db.sql.table")
            .requiresAttribute("INSERT referral_outbox", "db.sql.table")
            .requiresSameTrace(
                qualifySpan,
                "INSERT referral_reward",
                "INSERT referral_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("referral-qualify-invite")
    }
}
