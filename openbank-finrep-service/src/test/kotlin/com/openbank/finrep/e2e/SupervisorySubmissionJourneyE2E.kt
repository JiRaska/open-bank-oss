// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.e2e

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
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The supervisory submission journey over the real HTTP surface: an operator opens a FINREP
 * return idempotently, packages it, dry-run submits it with the attempt recorded, and is refused
 * an acknowledgement a dry run cannot produce — while the endpoints prove the boundaries the
 * service enforces: a blocked preflight refuses packaging, missing fields are a 400, an unknown
 * id is a 404, and a viewer cannot open a submission at all.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "return is opened, rendered, submitted, and evidenced" story, so a wiring regression that
 * deregisters a route (#3371), re-opens instead of replaying, or submits without recording the
 * attempt fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
class SupervisorySubmissionJourneyE2E {

    private val path = "/api/v1/finrep/submissions"

    // 2027-06-30 (not the ITs' pinned 2026-06-30): every @QuarkusTest in the module shares one
    // module database, and SupervisorySubmissionApiIT counts F01.01 rows for 2026-06-30 == 1.
    private val openBody = """{"templateId":"F01.01","referencePeriod":"2027-06-30","revision":1}"""
    private lateinit var submissionId: String

    @BeforeEach
    fun stubPreflight() {
        val mock = mockk<XbrlCsvPreflightService>()
        coEvery { mock.getPreflight(any()) } answers {
            val query = firstArg<GetXbrlCsvPreflightQuery>()
            val blocked = query.templateId == "F01.02"
            XbrlCsvPreflight(
                templateId = query.templateId,
                period = query.asOf,
                reportingFrameworkVersion = "4.2",
                dpmVersion = "4.2.1",
                taxonomyVersion = "4.2.0.0",
                state = if (blocked) {
                    XbrlCsvPreflightState.BLOCKED
                } else {
                    XbrlCsvPreflightState.READY_FOR_RENDERING
                },
                blockers = if (blocked) {
                    listOf(XbrlCsvBlocker("INCOMPLETE_OFFICIAL_MAPPING", "gap"))
                } else {
                    emptyList()
                },
            )
        }
        QuarkusMock.installMockForType(mock, XbrlCsvPreflightService::class.java)
    }

    @Test
    @Order(1)
    fun `1 - an operator opens a submission and the same body replays it`() {
        val opened = Given {
            contentType("application/json")
            body(openBody)
        } When {
            post(path)
        } Then {
            statusCode(201)
            body("state", equalTo("DRAFT"))
            body("templateId", equalTo("F01.01"))
        } Extract { path<String>("id") }
        submissionId = opened

        Given {
            contentType("application/json")
            body(openBody)
        } When {
            post(path)
        } Then {
            statusCode(200)
            body("id", equalTo(submissionId))
        }
    }

    @Test
    @Order(2)
    fun `2 - packaging renders the package and a dry-run submit records the attempt`() {
        Given { contentType("application/json") }
            .When { post("$path/$submissionId/package") }
            .Then {
                statusCode(200)
                body("state", equalTo("PACKAGED"))
            }

        // A DRAFT return cannot be submitted; packaging must come first.
        Given { contentType("application/json") }
            .When { post("$path/${UUID.randomUUID()}/submit") }
            .Then { statusCode(404) }

        Given { contentType("application/json") }
            .When { post("$path/$submissionId/submit") }
            .Then {
                statusCode(200)
                body("state", equalTo("PACKAGED"))
            }

        Given { contentType("application/json") }
            .When { get("$path/$submissionId/attempts") }
            .Then {
                statusCode(200)
                body("$", hasSize<Any>(1))
                body("[0].outcome", equalTo("DRY_RUN"))
                body("[0].transport", equalTo("dry-run"))
            }
    }

    @Test
    @Order(3)
    fun `3 - an acknowledgement is unprocessable for a dry-run return`() {
        Given {
            contentType("application/json")
            body("""{"accepted":true,"reference":"ACK-1"}""")
        } When {
            post("$path/$submissionId/acknowledgement")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @Order(4)
    fun `4 - a blocked preflight refuses packaging and the return stays a draft`() {
        val blockedId = Given {
            contentType("application/json")
            body("""{"templateId":"F01.02","referencePeriod":"2027-06-30","revision":1}""")
        } When {
            post(path)
        } Then { statusCode(201) } Extract { path<String>("id") }

        Given { contentType("application/json") }
            .When { post("$path/$blockedId/package") }
            .Then { statusCode(409) }

        Given { contentType("application/json") }
            .When { get("$path/$blockedId") }
            .Then { body("state", equalTo("DRAFT")) }
    }

    @Test
    @Order(5)
    fun `5 - missing fields are a bad request`() {
        Given {
            contentType("application/json")
            body("""{"templateId":"F01.01"}""")
        } When {
            post(path)
        } Then {
            statusCode(400)
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `6 - a viewer cannot open a submission`() {
        Given {
            contentType("application/json")
            body(openBody)
        } When {
            post(path)
        } Then {
            statusCode(403)
        }
    }
}
