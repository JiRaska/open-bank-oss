// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.contract

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
 * Consumer contract for the Admin UI BFF. The UI has no Pact runtime, so this test declares its
 * literal HTTP shape and commits the generated git-pact; the provider replay lives alongside it.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-flaky-test-hunter", pactVersion = PactSpecVersion.V3)
class FlakyTestTriggerPactConsumerTest {

    @Pact(consumer = "openbank-admin-ui", provider = "openbank-flaky-test-hunter")
    fun asyncTriggerPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an administrator may admit a flaky-test workflow")
        .uponReceiving("admit the bounded flaky-test workflow")
        .path("/api/v1/flaky-test-hunter/check/trigger-async-idempotent")
        .method("POST")
        .matchHeader("Authorization", "Bearer [A-Za-z0-9._-]+", "Bearer pact-admin-token")
        .matchHeader(
            "Idempotency-Key",
            "^flaky-test-hunter-operator-manual-\\d{4}-\\d{2}-\\d{2}$",
            IDEMPOTENCY_KEY,
        )
        .willRespondWith()
        .status(202)
        .headers(mapOf("Content-Type" to "application/json"))
        .body("""{"workflowId":"$WORKFLOW_ID"}""")
        .toPact()

    @Test
    @PactTestFor(pactMethod = "asyncTriggerPact")
    fun `admin UI receives an admission handle`(mockServer: MockServer) {
        val workflowId = given()
            .header("Authorization", "Bearer pact-admin-token")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .`when`().post("${mockServer.getUrl()}/api/v1/flaky-test-hunter/check/trigger-async-idempotent")
            .then().statusCode(202)
            .extract().jsonPath().getString("workflowId")

        assertThat(workflowId).isEqualTo(WORKFLOW_ID)
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "flaky-test-hunter-operator-manual-2026-08-18"
        const val WORKFLOW_ID = "flaky-test-hunter-check-operator_manual-2026-08-18"
    }
}
