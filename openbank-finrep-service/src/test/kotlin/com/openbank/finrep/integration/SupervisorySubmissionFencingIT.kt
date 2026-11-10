// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.integration

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.port.out.TransportResult
import com.openbank.finrep.application.usecase.XbrlCsvPreflightService
import com.openbank.finrep.domain.model.TransportOutcome
import com.openbank.finrep.domain.model.XbrlCsvPreflight
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import com.openbank.finrep.infrastructure.transport.DryRunSupervisoryReturnTransport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Claim fencing. Holder A claims and is stuck in the transport; its lease lapses, an ADMIN
 * reconciles the return to PACKAGED, and holder B claims and files it. When A's transport finally
 * answers, A's transition must move nothing (its claim id no longer matches) and A's late result
 * is recorded only as an UNKNOWN attempt under A's own claim id. Without the claim_id fence, A's
 * `WHERE state = 'SUBMITTING'` update would land on B's row.
 */
@QuarkusTest
class SupervisorySubmissionFencingIT {
    private val path = "/api/v1/finrep/submissions"
    private val calls = AtomicInteger()
    private val aEntered = CountDownLatch(1)
    private val releaseA = CountDownLatch(1)

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
        every { transport.name } returns "fencing-test"
        coEvery { transport.transmit(any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                aEntered.countDown()
                withContext(Dispatchers.IO) { releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS) }
                TransportResult(TransportOutcome.SUBMITTED, reference = "REF-A")
            } else {
                TransportResult(TransportOutcome.SUBMITTED, reference = "REF-B")
            }
        }
        QuarkusMock.installMockForType(transport, DryRunSupervisoryReturnTransport::class.java)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `a lapsed holder's late result never overwrites a newer claimant`() {
        val id = packaged()

        // Holder A claims and blocks inside the transport.
        val holderA = CompletableFuture.supplyAsync { RestAssured.given().post("$path/$id/submit").statusCode }
        assertThat(aEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        val claimA = scalar("select claim_id::text from finrep_submission where id = '$id'::uuid")
        assertThat(claimA).isNotNull()

        // A's lease lapses; the next submit lapses it to OUTCOME_UNKNOWN; an ADMIN releases it.
        execute("update finrep_submission set claimed_at = now() - interval '1 hour' where id = '$id'::uuid")
        Given { this } When { post("$path/$id/submit") } Then { body("state", equalTo("OUTCOME_UNKNOWN")) }
        Given {
            contentType("application/json")
            body("""{"sent":false,"note":"supervisor has no record"}""")
        } When { post("$path/$id/reconcile") } Then { body("state", equalTo("PACKAGED")) }

        // Holder B claims and files.
        Given { this } When { post("$path/$id/submit") } Then {
            statusCode(200)
            body("state", equalTo("SUBMITTED"))
            body("transportReference", equalTo("REF-B"))
        }

        // A finally answers. Its transition must be a no-op.
        releaseA.countDown()
        assertThat(holderA.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(200)

        assertThat(calls.get()).isEqualTo(2)
        assertThat(scalar("select transport_reference from finrep_submission where id = '$id'::uuid"))
            .isEqualTo("REF-B")
        assertThat(scalar("select state from finrep_submission where id = '$id'::uuid")).isEqualTo("SUBMITTED")
        assertThat(
            scalar(
                "select string_agg(outcome, ',' order by attempt_no) from finrep_submission_attempt " +
                    "where submission_id = '$id'::uuid and claim_id = '$claimA'::uuid and transport = 'fencing-test'",
            ),
        ).isEqualTo("UNKNOWN")
        assertThat(
            scalar(
                "select count(*)::text from finrep_submission_attempt where submission_id = '$id'::uuid " +
                    "and outcome = 'SUBMITTED' and transport = 'fencing-test' and claim_id <> '$claimA'::uuid",
            ),
        ).isEqualTo("1")
    }

    private fun packaged(): String {
        val id: String = Given {
            contentType("application/json")
            body("""{"templateId":"F01.01","referencePeriod":"2027-01-31","revision":1}""")
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

    private companion object {
        const val WAIT_SECONDS = 30L
    }
}
