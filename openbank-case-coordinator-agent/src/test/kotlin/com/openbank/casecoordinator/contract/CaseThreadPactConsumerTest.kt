// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the Phase 2 case read API (#4185), with **openbank-admin-ui as the
 * consumer** — the ADR-0246 thread view (Phase 3, #4186) renders exactly these two calls:
 * `GET /api/v1/case-coordinator/cases` (roster) and `GET /api/v1/case-coordinator/cases/{caseId}`
 * (thread). admin-ui is a Next.js app with no Pact tooling of its own, so this Kotlin test stands
 * in for its HTTP shape; the paths are written as LITERALS (never reflected off server-side
 * annotations — the #2283 asymmetry: only a literal can go red when the route moves).
 *
 * The generated pact is committed to `pacts/openbank-admin-ui-openbank-case-coordinator-agent.json`
 * (git-pact, ADR-0063) and replayed on every PR by `CaseCoordinatorPactProviderVerificationTest`
 * in this module. Regenerate after any contract change:
 * `./gradlew :openbank-case-coordinator-agent:test --tests "*CaseThreadPactConsumerTest*"` and
 * commit the pact JSON in the same PR — `pact-drift-check.yml` diffs `pacts/` and fails on drift.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-case-coordinator-agent", pactVersion = PactSpecVersion.V3)
class CaseThreadPactConsumerTest {

    @Pact(consumer = "openbank-admin-ui", provider = "openbank-case-coordinator-agent")
    fun caseListPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an open case exists")
        .uponReceiving("list open cases for the swarm roster")
        .path("/api/v1/case-coordinator/cases")
        .query("status=OPEN")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            """
            {
              "cases": [
                {
                  "caseId": "case-incident-response-alert-7",
                  "caseClass": "INCIDENT_RESPONSE",
                  "dispositionTarget": "alert-7",
                  "status": "OPEN",
                  "openedAtEpochMs": 1760000000000,
                  "deadlineAtEpochMs": 1760001200000,
                  "contestedRate": 0.0,
                  "contributionCount": 1
                }
              ]
            }
            """.trimIndent(),
        )
        .toPact()

    @Pact(consumer = "openbank-admin-ui", provider = "openbank-case-coordinator-agent")
    fun caseThreadPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a closed case with a thread exists")
        .uponReceiving("fetch the swarm thread for one case")
        .path("/api/v1/case-coordinator/cases/case-incident-response-alert-7")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            """
            {
              "caseId": "case-incident-response-alert-7",
              "caseClass": "INCIDENT_RESPONSE",
              "dispositionTarget": "alert-7",
              "status": "CLOSED",
              "openedAtEpochMs": 1760000000000,
              "deadlineAtEpochMs": 1760001200000,
              "contestedRate": 0.5,
              "entries": [
                {"type": "CASE_OPENED", "atEpochMs": 1760000000000, "summary": "alert-7",
                 "evidenceRefs": [], "superseded": false, "contested": false},
                {"type": "CONTRIBUTION", "atEpochMs": 1760000060000, "actor": "fraud-agent",
                 "summary": "velocity spike", "evidenceRefs": ["tx-1"], "draftVersion": 1,
                 "superseded": false, "contested": true},
                {"type": "PROPOSAL_EMITTED", "atEpochMs": 1760000120000,
                 "proposalId": "11111111-1111-1111-1111-111111111111",
                 "proposalType": "case-synthesis", "evidenceRefs": [],
                 "superseded": false, "contested": false}
              ]
            }
            """.trimIndent(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "caseListPact")
    fun `list open cases`(mockServer: MockServer) {
        val body = given()
            .`when`().get("${mockServer.getUrl()}/api/v1/case-coordinator/cases?status=OPEN")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getList<Map<String, Any>>("cases")).hasSize(1)
        assertThat(body.getString("cases[0].caseId")).isEqualTo("case-incident-response-alert-7")
        assertThat(body.getInt("cases[0].contributionCount")).isEqualTo(1)
    }

    @Test
    @PactTestFor(pactMethod = "caseThreadPact")
    fun `fetch case thread`(mockServer: MockServer) {
        val body = given()
            .`when`().get("${mockServer.getUrl()}/api/v1/case-coordinator/cases/case-incident-response-alert-7")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getList<Map<String, Any>>("entries")).hasSize(3)
        assertThat(body.getString("entries[1].actor")).isEqualTo("fraud-agent")
        assertThat(body.getBoolean("entries[1].contested")).isTrue()
        assertThat(body.getString("entries[2].type")).isEqualTo("PROPOSAL_EMITTED")
    }
}
