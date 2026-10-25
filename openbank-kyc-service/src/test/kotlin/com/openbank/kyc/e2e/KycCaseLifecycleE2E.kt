// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The operator-facing case lifecycle over real HTTP + Postgres: open (201) and duplicate-open
 * (409), the funnel list with its status filter and echoed page bounds, check updates driving
 * the case to UNDER_REVIEW, the four-eyes approve/reject with the reviewer identity taken from
 * the security context (never the body), and the 4xx family the ČNB mandate pins
 * (404 unknown case, 422 short reason, 422 wrong state, 400 unknown check type, 403 role gate).
 */
@QuarkusTest
@TestProfile(KycE2EProfile::class)
class KycCaseLifecycleE2E : KycE2EBase() {

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `opening a case returns 201 with an OPEN case carrying the four mandatory checks`() {
        val partyId = UUID.randomUUID()
        val caseId = openCase(partyId)

        assertThat(caseRow(caseId).status).isEqualTo("OPEN")
        assertThat(checksViaApi(caseId)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "IDENTITY" to "PENDING",
                "ADDRESS" to "PENDING",
                "PEP_SCREENING" to "PENDING",
                "SANCTIONS_SCREENING" to "PENDING",
            ),
        )
        assertThat(outboxEventTypes(caseId)).containsExactly("KYC_CASE_OPENED")
        val payload = outboxPayload(caseId, "KYC_CASE_OPENED")
        assertThat(payload).contains("\"eventType\":\"KYC_CASE_OPENED\"")
        assertThat(payload).contains("\"kycCaseId\":\"$caseId\"")
        assertThat(payload).contains("\"partyId\":\"$partyId\"")
        assertThat(payload).contains("\"sourceService\":\"kyc-service\"")
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a second open for the same party answers 409 while the case is active`() {
        val partyId = UUID.randomUUID()
        val caseId = openCase(partyId)

        val errorCode = Given {
            contentType("application/json")
            body("""{"partyId":"$partyId"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(409)
        } Extract {
            path<String>("code")
        }
        assertThat(errorCode).isEqualTo("CONFLICT")

        // No second case, no second OPENED event.
        assertThat(outboxEventTypes(caseId)).containsExactly("KYC_CASE_OPENED")
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    fun `after a terminal verdict a fresh case may be opened again for the party`() {
        val partyId = UUID.randomUUID()
        val first = openCase(partyId)
        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach {
            passCheck(first, it)
        }

        Given {
            contentType("application/json")
            body("""{"reason":"documents do not meet the E2E bar"}""")
        } When {
            post("/api/v1/kyc/cases/$first/reject")
        } Then {
            statusCode(200)
        }

        val second = openCase(partyId)
        assertThat(second).isNotEqualTo(first)
        assertThat(caseRow(second).status).isEqualTo("OPEN")
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    fun `the funnel list filters by status and echoes the clamped page bounds`() {
        val partyId = UUID.randomUUID()
        val openCaseId = openCase(partyId)
        val reviewCaseId = openCase(UUID.randomUUID())
        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach {
            passCheck(reviewCaseId, it)
        }
        assertThat(caseRow(reviewCaseId).status).isEqualTo("UNDER_REVIEW")

        val openPage = Given { this } When {
            get("/api/v1/kyc/cases?status=OPEN&size=500&page=-1")
        } Then {
            statusCode(200)
        } Extract {
            Triple(
                jsonPath().getList<String>("items.id").map { UUID.fromString(it) },
                jsonPath().getInt("size"),
                jsonPath().getInt("page"),
            )
        }
        // `size` is clamped to the published 1..100 window and echoed clamped, `page` floors at 0
        // — a pager must never see the raw out-of-range values it sent.
        assertThat(openPage.second).isEqualTo(100)
        assertThat(openPage.third).isEqualTo(0)
        assertThat(openPage.first).contains(openCaseId).doesNotContain(reviewCaseId)
        assertThat(jsonPathUnfilteredIds()).contains(openCaseId, reviewCaseId)
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    fun `an unparseable status filter answers 400 instead of silently dropping the condition`() {
        Given { this } When {
            get("/api/v1/kyc/cases?status=NOPE")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    fun `all four checks passed routes the case to UNDER_REVIEW and one failed check rejects it`() {
        val rejectedCase = openCase(UUID.randomUUID())
        passCheck(rejectedCase, "IDENTITY")
        Given {
            contentType("application/json")
            body("""{"status":"FAILED","result":"document unreadable"}""")
        } When {
            put("/api/v1/kyc/cases/$rejectedCase/checks/ADDRESS")
        } Then {
            statusCode(200)
        }
        assertThat(caseRow(rejectedCase).status).isEqualTo("REJECTED")
        assertThat(outboxEventTypes(rejectedCase))
            .containsExactly("KYC_CASE_OPENED", "KYC_CASE_STATUS_CHANGED")

        val passingCase = openCase(UUID.randomUUID())
        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach {
            passCheck(passingCase, it)
        }
        assertThat(caseRow(passingCase).status).isEqualTo("UNDER_REVIEW")
        assertThat(outboxEventTypes(passingCase))
            .containsExactly("KYC_CASE_OPENED", "KYC_CASE_STATUS_CHANGED")
    }

    @Test
    @TestSecurity(user = "e2e-reviewer", roles = ["ROLE_KYC_OPENER", "ROLE_KYC_REVIEWER"])
    fun `approving an UNDER_REVIEW case records the reviewer from the context and publishes APPROVED`() {
        // One journey identity carrying opener+reviewer roles; the four-eyes mandate this
        // asserts is that the reviewer identity comes from the security context (never the
        // request body) and lands on the row, the notes and the event actor.
        val caseId = openCase(UUID.randomUUID())
        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach {
            passCheck(caseId, it)
        }

        val reviewedBy = Given {
            contentType("application/json")
            body("""{"reason":"all checks verified against source documents"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/approve")
        } Then {
            statusCode(200)
        } Extract {
            path<String>("reviewedBy")
        }

        // The identity comes from the security context, never from the request body.
        assertThat(reviewedBy).isEqualTo("e2e-reviewer")
        val row = caseRow(caseId)
        assertThat(row.status).isEqualTo("APPROVED")
        assertThat(row.reviewedBy).isEqualTo("e2e-reviewer")
        assertThat(row.notes).isEqualTo("all checks verified against source documents")

        assertThat(outboxEventTypes(caseId))
            .containsExactly("KYC_CASE_OPENED", "KYC_CASE_STATUS_CHANGED", "KYC_CASE_APPROVED")
        val payload = outboxPayload(caseId, "KYC_CASE_APPROVED")
        assertThat(payload).contains("\"eventType\":\"KYC_CASE_APPROVED\"")
        assertThat(payload).contains("\"actorId\":\"e2e-reviewer\"")
        assertThat(payload).contains("\"actorType\":\"REVIEWER\"")
    }

    @Test
    @TestSecurity(user = "e2e-reviewer", roles = ["ROLE_KYC_REVIEWER", "ROLE_ADMIN"])
    fun `the four-eyes 4xx family is pinned over real HTTP`() {
        val openCaseId = openCase(UUID.randomUUID())

        // Unknown case: 404 from the libs-domain not-found mapper.
        Given { this } When {
            get("/api/v1/kyc/cases/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }

        // Unknown party: the by-party lookup answers an empty 404.
        Given { this } When {
            get("/api/v1/kyc/cases/party/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }

        // Approve on an OPEN case (not UNDER_REVIEW): 422 INVALID_STATE_TRANSITION.
        val stateCode = Given {
            contentType("application/json")
            body("""{"reason":"this reason is long enough"}""")
        } When {
            post("/api/v1/kyc/cases/$openCaseId/approve")
        } Then {
            statusCode(422)
        } Extract {
            path<String>("code")
        }
        assertThat(stateCode).isEqualTo("INVALID_STATE_TRANSITION")

        // Reason under the 10-character regulatory minimum: 422 INVALID_APPROVAL_REASON.
        Given {
            contentType("application/json")
            body("""{"reason":"short"}""")
        } When {
            post("/api/v1/kyc/cases/$openCaseId/approve")
        } Then {
            statusCode(422)
        }

        // Unknown check type on the enum: 400 from libs-runtime's IllegalArgumentException mapping.
        Given {
            contentType("application/json")
            body("""{"status":"PASSED","result":"x"}""")
        } When {
            put("/api/v1/kyc/cases/$openCaseId/checks/ASTROLOGY")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a viewer can read the funnel but cannot open a case`() {
        Given { this } When {
            get("/api/v1/kyc/cases")
        } Then {
            statusCode(200)
        }
        Given {
            contentType("application/json")
            body("""{"partyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "e2e-opener", roles = ["ROLE_KYC_OPENER"])
    fun `an opener can update checks but never approve - maker-checker holds over HTTP`() {
        val caseId = openCase(UUID.randomUUID())

        Given {
            contentType("application/json")
            body("""{"status":"PASSED","result":"e2e"}""")
        } When {
            put("/api/v1/kyc/cases/$caseId/checks/IDENTITY")
        } Then {
            statusCode(200)
        }
        Given {
            contentType("application/json")
            body("""{"reason":"opener must never approve a case"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/approve")
        } Then {
            statusCode(403)
        }
        assertThat(caseRow(caseId).status).isEqualTo("OPEN")
    }

    private fun jsonPathUnfilteredIds(): List<UUID> = Given { this } When {
        get("/api/v1/kyc/cases?size=100")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getList<String>("items.id").map { UUID.fromString(it) }
    }
}
