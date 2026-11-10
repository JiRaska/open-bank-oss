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
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A live regulator adapter called twice is a double filing. Two parallel submits of one PACKAGED
 * return must reach the transport exactly once (the PACKAGED -> SUBMITTING claim), and two parallel
 * opens of one revision must answer 201 + 200 over a single row, never a 500.
 *
 * The transport is slow on purpose (TRANSMIT_MILLIS), so both requests are in flight together.
 */
@QuarkusTest
class SupervisorySubmissionConcurrencyIT {
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
        every { transport.name } returns "slow-test"
        coEvery { transport.transmit(any()) } coAnswers {
            transportCalls.incrementAndGet()
            delay(TRANSMIT_MILLIS)
            TransportResult(TransportOutcome.SUBMITTED, reference = "REF-1")
        }
        QuarkusMock.installMockForType(transport, DryRunSupervisoryReturnTransport::class.java)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `two parallel submits call the transport exactly once`() {
        val id: String = Given {
            contentType("application/json")
            body("""{"templateId":"F01.01","referencePeriod":"2026-09-30","revision":1}""")
        } When { post(path) } Then { statusCode(201) } Extract { path("id") }
        Given { this } When { post("$path/$id/package") } Then { statusCode(200) }

        val statuses = parallel(2) { RestAssured.given().post("$path/$id/submit").statusCode }

        assertThat(transportCalls.get()).isEqualTo(1)
        assertThat(statuses).containsAnyOf(200).doesNotContain(500)
        assertThat(statuses.filter { it != 200 }).allMatch { it == 409 }
        assertThat(
            count("select count(*) from finrep_submission_attempt where submission_id = '$id'::uuid"),
        ).isEqualTo(1)
        assertThat(
            count("select count(*) from finrep_submission where id = '$id'::uuid and state = 'SUBMITTED'"),
        ).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `two parallel opens of one revision never answer 500 and leave one row`() {
        val body = """{"templateId":"F01.03","referencePeriod":"2026-09-30","revision":7}"""
        val statuses = parallel(2) {
            RestAssured.given().contentType("application/json").body(body).post(path).statusCode
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 200)
        assertThat(
            count("select count(*) from finrep_submission where template_id = 'F01.03' and revision = 7"),
        ).isEqualTo(1)
    }

    private fun parallel(n: Int, call: () -> Int): List<Int> {
        val start = CountDownLatch(1)
        val futures = (1..n).map {
            CompletableFuture.supplyAsync {
                start.await()
                call()
            }
        }
        start.countDown()
        return futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    private fun count(sql: String): Int {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    private companion object {
        const val TRANSMIT_MILLIS = 1500L
        const val WAIT_SECONDS = 30L
    }
}
