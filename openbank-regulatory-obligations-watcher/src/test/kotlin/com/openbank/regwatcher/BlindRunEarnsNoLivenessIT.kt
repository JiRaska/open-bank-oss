// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.regwatcher.application.port.DraftTicketPolicyPort
import com.openbank.regwatcher.application.port.GovernanceIssuePort
import com.openbank.regwatcher.domain.FindingKind
import com.openbank.regwatcher.infrastructure.schedule.WatchRunner
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * A run that read NOTHING must not record liveness success. Its own profile so the application —
 * and therefore the liveness gauge — is fresh: the assertion is that `success_recorded` is still 0
 * after a completed run in which every source came back DENIED or NOT_RENDERABLE.
 *
 * Falsified by hand: making WatchRunResult.livenessEarned return `skippedReason == null` (dropping
 * the `fetched > 0` clause) turns this test red.
 */
@QuarkusTest
@TestProfile(BlindRunEarnsNoLivenessIT.Blind::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(StubBroker::class)
class BlindRunEarnsNoLivenessIT {
    class Blind : QuarkusTestProfile {
        override fun getConfigProfile() = "test"
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a run with zero FETCHED sources records no liveness success`() {
        val issues = RecordingIssues()
        QuarkusMock.installMockForType(FakeModel(), LlmGatewayPort::class.java)
        QuarkusMock.installMockForType(issues, GovernanceIssuePort::class.java)
        QuarkusMock.installMockForType(AllowPolicy(), DraftTicketPolicyPort::class.java)
        StubBroker.pages.clear()
        StubBroker.pages[WatchRunEndToEndIT.LAW] = StubBroker.Page("DENIED", null, null, "robots.txt disallows")
        StubBroker.pages[WatchRunEndToEndIT.SPEC] = StubBroker.Page("NOT_RENDERABLE", null, null, "empty body")
        // EURLEX: absent from the stub, which answers DENIED.

        given().post("/api/v1/regulatory-watcher/run").then().statusCode(200)
            .body("fetched", equalTo(0)).body("unverified", equalTo(3))

        assertThat(issues.filed).allMatch { it.kind == FindingKind.UNVERIFIED_SOURCE }
        val gauge = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
            .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WatchRunner.WORKFLOW).gauge()
        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(0.0)
    }
}
