// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.rest

import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/**
 * The agent identity a call ACTS AS must be authorised against the identity it PROVED (#4834).
 *
 * Driven over real HTTP so the principal and its roles are real: a unit test that constructs the
 * resource and hands it a mock `SecurityIdentity` cannot tell an enforced binding from a
 * decorative one, because the mock supplies whatever roles the test wants either way.
 *
 * The profile is what makes the defect observable at all. On the shipped defaults `openAgents`
 * holds exactly one entry, so the only string that passes `canOpenCase` is also the only string
 * anyone is bound to — the hole is real but inert, and no assertion can separate the two. This
 * profile grants the capability list a second chartered agent (`incident-triage`) and binds
 * `ROLE_OPERATOR` to only the first, which is precisely the state the config comment says is
 * coming ("swarm join/contribute grants are deliberate follow-up charter work").
 *
 * Read the pairs, not the single status. Temporal is disabled in `%test`. An authorised case-open
 * stops at 503; an authorised collaboration signal reaches the server-owned case lookup and stops
 * at 404 because this profile created no case row. Both prove the identity gate passed, while 403
 * proves it refused the asserted identity before either downstream decision.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(CaseAssertedIdentityIT.TwoCharteredAgentsProfile::class)
class CaseAssertedIdentityIT {

    /**
     * Literal values only, never a randomised id: a `QuarkusTestProfile` loads in a different
     * classloader from the test class, so anything computed in a companion object initialises
     * twice and the scheduler/config sees one value while the assertion sees another.
     */
    class TwoCharteredAgentsProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.case-coordinator.case.open-agents" to "case-coordinator,incident-triage",
            "openbank.case-coordinator.case.swarm-agents" to "case-coordinator,incident-triage",
            "openbank.case-coordinator.case.identity-bindings" to "ROLE_OPERATOR=case-coordinator",
        )
    }

    private fun openCase(openedBy: String) = given()
        .contentType(ContentType.JSON)
        .body(
            mapOf(
                "caseClass" to "INCIDENT_RESPONSE",
                "subjectRef" to "acct-4834",
                "openedBy" to openedBy,
                "dispositionTarget" to "alert-4834",
            ),
        )
        .`when`().post("/api/v1/case-coordinator/cases")
        .then()

    private fun signal(agentId: String) = given()
        .contentType(ContentType.JSON)
        .body(mapOf("type" to "contribute", "agentId" to agentId, "summary" to "s"))
        .`when`().post("/api/v1/case-coordinator/cases/case-incident-response-acct-4834/signals")
        .then()

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `openCase refuses an agent identity the caller's role is not bound to`() {
        openCase("incident-triage").statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `openCase still accepts the agent identity the caller's role IS bound to`() {
        // The must-ALLOW control: 503 is the Temporal-disabled stop, reached only after every
        // authorisation decision passed. Without it a blanket 403 would satisfy the test above.
        openCase("case-coordinator").statusCode(503)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a denied openCase does not echo the asserted identity back`() {
        openCase("incident-triage")
            .statusCode(403)
            .body("error", not(containsString("incident-triage")))
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `signal refuses an agent identity the caller's role is not bound to`() {
        signal("incident-triage").statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `signal still accepts the agent identity the caller's role IS bound to`() {
        signal("case-coordinator").statusCode(404)
    }

    @Test
    @TestSecurity(user = "admin-1", roles = ["ROLE_ADMIN"])
    fun `a role with no binding at all may assert no agent identity`() {
        // Deny-by-default: the profile binds only ROLE_OPERATOR, so ROLE_ADMIN — which the
        // endpoint's @RolesAllowed still admits — can assert nothing. An unbound role must not
        // fall through to "allowed".
        openCase("case-coordinator").statusCode(403)
    }
}
