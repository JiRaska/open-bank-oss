// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The operator-triggered PEP re-screen (ADR-0116 first increment) over real HTTP: a CLEAR screen
 * passes the PEP check, a match routes it to MANUAL_REVIEW and escalates the risk floor to HIGH
 * (never an automated verdict), and an UNAVAILABLE downstream can never masquerade as a clean
 * screen. The downstream sanctions-service call is replaced by [E2EPepScreeningStub], so each
 * outcome is deterministic and the screen's inputs are asserted from the recorded calls.
 */
@QuarkusTest
@TestProfile(KycE2EProfile::class)
class KycPepScreeningE2E : KycE2EBase() {

    @Inject
    lateinit var stub: E2EPepScreeningStub

    @BeforeEach
    fun resetStubBefore() {
        // The Kafka journeys share this JVM and record their open-time screens on the stub —
        // each test starts from a clean call log.
        stub.reset()
    }

    @AfterEach
    fun resetStub() {
        stub.reset()
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE", "ROLE_KYC_OPENER"])
    fun `a CLEAR re-screen passes the PEP check and leaves the risk tier alone`() {
        val caseId = openCase(UUID.randomUUID())
        val partyName = "E2E Clear Person ${UUID.randomUUID()}"

        val riskLevel = Given {
            contentType("application/json")
            body("""{"partyName":"$partyName"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/pep-rescreen")
        } Then {
            statusCode(200)
        } Extract {
            path<String>("riskLevel")
        }

        assertThat(riskLevel).isEqualTo("MEDIUM")
        assertThat(checksViaApi(caseId)["PEP_SCREENING"]).isEqualTo("PASSED")
        assertThat(stub.calls).containsExactly(partyName to "kyc-pep-$caseId")
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE", "ROLE_KYC_OPENER"])
    fun `a PEP match routes the check to MANUAL_REVIEW and escalates the risk to HIGH without rejecting`() {
        stub.nextResult = PepScreeningResult(
            status = PepScreeningStatus.POTENTIAL_MATCH,
            matchScore = 0.91,
            matchedName = "E2E Politician",
        )
        val caseId = openCase(UUID.randomUUID())

        val riskLevel = Given {
            contentType("application/json")
            body("""{"partyName":"E2E Politician"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/pep-rescreen")
        } Then {
            statusCode(200)
        } Extract {
            path<String>("riskLevel")
        }

        assertThat(riskLevel).isEqualTo("HIGH")
        assertThat(caseRow(caseId).riskLevel).isEqualTo("HIGH")
        assertThat(checksViaApi(caseId)["PEP_SCREENING"]).isEqualTo("MANUAL_REVIEW")
        // The case itself stays OPEN — a PEP hit is never an automated verdict (ADR-0116).
        assertThat(caseRow(caseId).status).isEqualTo("OPEN")
        assertThat(outboxEventTypes(caseId))
            .containsExactly("KYC_CASE_OPENED", "KYC_CASE_STATUS_CHANGED")
    }

    @Test
    @TestSecurity(
        user = "e2e-compliance",
        roles = ["ROLE_COMPLIANCE", "ROLE_KYC_OPENER", "ROLE_KYC_REVIEWER"],
    )
    fun `even with every other check passed a MANUAL_REVIEW PEP check blocks the four-eyes gate`() {
        stub.nextResult = PepScreeningResult(
            status = PepScreeningStatus.MATCH,
            matchScore = 0.97,
            matchedName = "E2E Sanctioned Minister",
        )
        val caseId = openCase(UUID.randomUUID())
        listOf("IDENTITY", "ADDRESS", "SANCTIONS_SCREENING").forEach { passCheck(caseId, it) }

        Given {
            contentType("application/json")
            body("""{"partyName":"E2E Sanctioned Minister"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/pep-rescreen")
        } Then {
            statusCode(200)
        }

        // All checks but the PEP one are PASSED — the case must NOT have reached UNDER_REVIEW.
        assertThat(caseRow(caseId).status).isEqualTo("OPEN")

        Given {
            contentType("application/json")
            body("""{"reason":"trying to skip the PEP review"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/approve")
        } Then {
            statusCode(422)
        }
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE", "ROLE_KYC_OPENER"])
    fun `an unavailable screen is recorded as MANUAL_REVIEW evidence, never a false PASSED`() {
        stub.nextResult = PepScreeningResult(
            status = PepScreeningStatus.UNAVAILABLE,
            matchScore = 0.0,
            matchedName = null,
        )
        val caseId = openCase(UUID.randomUUID())

        Given {
            contentType("application/json")
            body("""{"partyName":"E2E Unreachable Probe"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/pep-rescreen")
        } Then {
            statusCode(200)
        }

        val checks = checksViaApi(caseId)
        assertThat(checks["PEP_SCREENING"]).isEqualTo("MANUAL_REVIEW")
        assertThat(caseRow(caseId).riskLevel).isEqualTo("MEDIUM")
    }

    @Test
    @TestSecurity(user = "e2e-compliance", roles = ["ROLE_COMPLIANCE", "ROLE_KYC_OPENER"])
    fun `re-screening an unknown case answers 404`() {
        Given {
            contentType("application/json")
            body("""{"partyName":"Nobody"}""")
        } When {
            post("/api/v1/kyc/cases/${UUID.randomUUID()}/pep-rescreen")
        } Then {
            statusCode(404)
        }
    }
}
