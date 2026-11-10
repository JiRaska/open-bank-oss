// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.integration

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.usecase.XbrlCsvPreflightService
import com.openbank.finrep.domain.model.XbrlCsvPreflight
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import com.openbank.finrep.infrastructure.transport.DryRunSupervisoryReturnTransport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * A claimed submission must never be left SUBMITTING by a failure, and must never be resubmitted
 * when the transport may have delivered: a throwing transport and a lapsed claim lease both land in
 * OUTCOME_UNKNOWN, with an UNKNOWN attempt row, and only an ADMIN reconcile resolves it.
 */
@QuarkusTest
class SupervisorySubmissionRecoveryIT {
    private val path = "/api/v1/finrep/submissions"
    private val transportCalls = AtomicInteger()

    @BeforeEach
    fun mocks() {
        val preflight = mockk<XbrlCsvPreflightService>()
        coEvery { preflight.getPreflight(any()) } answers {
            val q = firstArg<GetXbrlCsvPreflightQuery>()
            XbrlCsvPreflight(
                q.templateId,
                q.asOf,
                "4.2",
                "4.2.1",
                "4.2.0.0",
                XbrlCsvPreflightState.READY_FOR_RENDERING,
                emptyList(),
            )
        }
        QuarkusMock.installMockForType(preflight, XbrlCsvPreflightService::class.java)

        val transport = mockk<DryRunSupervisoryReturnTransport>()
        every { transport.name } returns "throwing-test"
        coEvery { transport.transmit(any()) } coAnswers {
            transportCalls.incrementAndGet()
            error("connection reset after the request was written")
        }
        QuarkusMock.installMockForType(transport, DryRunSupervisoryReturnTransport::class.java)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a throwing transport records UNKNOWN, blocks resubmit and leaves no SUBMITTING row`() {
        val id = packaged("2026-10-31", 1)

        Given { this } When { post("$path/$id/submit") } Then {
            statusCode(200)
            body("state", equalTo("OUTCOME_UNKNOWN"))
        }
        Given { this } When { post("$path/$id/submit") } Then { statusCode(409) }

        assertThat(transportCalls.get()).isEqualTo(1)
        assertThat(scalar("select state from finrep_submission where id = '$id'::uuid")).isEqualTo("OUTCOME_UNKNOWN")
        assertThat(scalar("select claimed_at::text from finrep_submission where id = '$id'::uuid")).isNull()
        assertThat(scalar("select outcome from finrep_submission_attempt where submission_id = '$id'::uuid"))
            .isEqualTo("UNKNOWN")
        // An OPERATOR cannot reconcile; that is an ADMIN decision.
        Given {
            contentType("application/json")
            body("""{"sent":false}""")
        } When { post("$path/$id/reconcile") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `a lapsed claim becomes OUTCOME_UNKNOWN without calling the transport, then ADMIN reconciles`() {
        val id = packaged("2026-11-30", 1)
        // Simulate a holder that died mid-transmission: SUBMITTING with a claim an hour old.
        execute(
            "update finrep_submission set state = 'SUBMITTING', claimed_at = now() - interval '1 hour' " +
                "where id = '$id'::uuid",
        )

        Given { this } When { post("$path/$id/submit") } Then {
            statusCode(200)
            body("state", equalTo("OUTCOME_UNKNOWN"))
        }
        assertThat(transportCalls.get()).isZero()
        assertThat(scalar("select transport from finrep_submission_attempt where submission_id = '$id'::uuid"))
            .isEqualTo("claim-lease")

        Given {
            contentType("application/json")
            body("""{"sent":true}""")
        } When { post("$path/$id/reconcile") } Then { statusCode(400) }
        Given {
            contentType("application/json")
            body("""{"sent":true,"reference":"SUP-42","note":"confirmed by phone"}""")
        } When { post("$path/$id/reconcile") } Then {
            statusCode(200)
            body("state", equalTo("SUBMITTED"))
            body("transportReference", equalTo("SUP-42"))
        }
        assertThat(scalar("select state from finrep_submission where id = '$id'::uuid")).isEqualTo("SUBMITTED")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a fresh claim is still in progress`() {
        val id = packaged("2026-12-31", 1)
        execute("update finrep_submission set state = 'SUBMITTING', claimed_at = now() where id = '$id'::uuid")

        Given { this } When { post("$path/$id/submit") } Then { statusCode(409) }
        assertThat(transportCalls.get()).isZero()
        assertThat(scalar("select state from finrep_submission where id = '$id'::uuid")).isEqualTo("SUBMITTING")
    }

    private fun packaged(period: String, revision: Int): String {
        val id: String = Given {
            contentType("application/json")
            body("""{"templateId":"F02.00","referencePeriod":"$period","revision":$revision}""")
        } When { post(path) } Then { statusCode(201) } Extract { path("id") }
        Given { this } When { post("$path/$id/package") } Then { statusCode(200) }
        return id
    }

    private fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private fun execute(sql: String) {
        withConnection { conn -> conn.createStatement().use { assertThat(it.executeUpdate(sql)).isEqualTo(1) } }
    }

    private fun scalar(sql: String): String? = withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                assertThat(rs.next()).isTrue()
                rs.getString(1)
            }
        }
    }
}
