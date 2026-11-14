// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.integration

import com.openbank.finrep.application.port.inbound.GetXbrlCsvPreflightQuery
import com.openbank.finrep.application.usecase.XbrlCsvPreflightService
import com.openbank.finrep.domain.model.XbrlCsvBlocker
import com.openbank.finrep.domain.model.XbrlCsvPreflight
import com.openbank.finrep.domain.model.XbrlCsvPreflightState
import com.openbank.libs.testing.containers.PostgresTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
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
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate

/**
 * Drives the submission lifecycle through real HTTP, CDI security, Flyway and Postgres, then reads
 * the rows back over plain JDBC. Not restricted to this class: every @QuarkusTest in the module
 * boots against this database now that finrep has one.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SupervisorySubmissionApiIT {
    private val path = "/api/v1/finrep/submissions"

    @BeforeEach
    fun preflight() {
        val mock = mockk<XbrlCsvPreflightService>()
        coEvery { mock.getPreflight(any()) } answers {
            val q = firstArg<GetXbrlCsvPreflightQuery>()
            val blocked = q.templateId == "F01.02"
            XbrlCsvPreflight(
                templateId = q.templateId,
                period = q.asOf,
                reportingFrameworkVersion = "4.2",
                dpmVersion = "4.2.1",
                taxonomyVersion = "4.2.0.0",
                state = if (blocked) XbrlCsvPreflightState.BLOCKED else XbrlCsvPreflightState.READY_FOR_RENDERING,
                blockers = if (blocked) listOf(XbrlCsvBlocker("INCOMPLETE_OFFICIAL_MAPPING", "gap")) else emptyList(),
            )
        }
        QuarkusMock.installMockForType(mock, XbrlCsvPreflightService::class.java)
    }

    @Test
    fun `anonymous caller is refused`() {
        Given { this } When { get(path) } Then { statusCode(401) }
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `viewer cannot open a submission`() {
        Given {
            contentType("application/json")
            body("""{"templateId":"F01.01","referencePeriod":"2026-03-31","revision":1}""")
        } When { post(path) } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `open is idempotent, dry-run submit records an attempt and leaves the return PACKAGED`() {
        val body = """{"templateId":"F01.01","referencePeriod":"2026-06-30","revision":1}"""
        val id: String = Given {
            contentType("application/json")
            body(body)
        } When { post(path) } Then {
            statusCode(201)
            body("state", equalTo("DRAFT"))
        } Extract { path("id") }
        Given {
            contentType("application/json")
            body(body)
        } When { post(path) } Then {
            statusCode(200)
            body("id", equalTo(id))
        }

        Given { this } When { post("$path/$id/submit") } Then { statusCode(409) }
        Given { this } When { post("$path/$id/package") } Then {
            statusCode(200)
            body("state", equalTo("PACKAGED"))
        }
        Given { this } When { post("$path/$id/submit") } Then {
            statusCode(200)
            body("state", equalTo("PACKAGED"))
        }
        Given { this } When { get("$path/$id/attempts") } Then {
            statusCode(200)
            body("$", hasSize<Any>(1))
            body("[0].outcome", equalTo("DRY_RUN"))
            body("[0].transport", equalTo("dry-run"))
        }
        // Acknowledgement is only meaningful for a SUBMITTED return; a dry run never produces one.
        Given {
            contentType("application/json")
            body("""{"accepted":true,"reference":"ACK-1"}""")
        } When { post("$path/$id/acknowledgement") } Then { statusCode(422) }

        assertDryRunRowsPersisted(id)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `packaging is refused while the preflight is blocked`() {
        val id: String = Given {
            contentType("application/json")
            body("""{"templateId":"F01.02","referencePeriod":"2026-06-30","revision":1}""")
        } When { post(path) } Then { statusCode(201) } Extract { path("id") }
        Given { this } When { post("$path/$id/package") } Then {
            statusCode(409)
        }
        Given { this } When { get("$path/$id") } Then { body("state", equalTo("DRAFT")) }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `missing fields are a 400, unknown id a 404`() {
        Given {
            contentType("application/json")
            body("""{"templateId":"F01.01"}""")
        } When { post(path) } Then {
            statusCode(400)
        }
        Given { this } When { get("$path/00000000-0000-0000-0000-000000000099") } Then { statusCode(404) }
    }

    private fun assertDryRunRowsPersisted(id: String) {
        jdbc { conn ->
            conn.prepareStatement(
                "select s.state, s.package_hash, a.outcome, a.attempted_by from finrep_submission s " +
                    "join finrep_submission_attempt a on a.submission_id = s.id where s.id = ?::uuid",
            ).use { st ->
                st.setString(1, id)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("PACKAGED")
                    assertThat(rs.getString(2)).hasSize(64)
                    assertThat(rs.getString(3)).isEqualTo("DRY_RUN")
                    assertThat(rs.getString(4)).isEqualTo("operator")
                    assertThat(rs.next()).isFalse()
                }
            }
            conn.prepareStatement(
                "select count(*) from finrep_submission where template_id = 'F01.01' and reference_period = ?",
            ).use { st ->
                st.setObject(1, LocalDate.parse("2026-06-30"))
                st.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(1)
                }
            }
        }
    }

    private fun jdbc(block: (java.sql.Connection) -> Unit) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }
}
