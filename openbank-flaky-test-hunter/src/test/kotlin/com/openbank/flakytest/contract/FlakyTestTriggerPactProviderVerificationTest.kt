// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.flakytest.PostgresTestResource
import com.openbank.flakytest.application.usecase.FlakyTestHunterService
import com.openbank.flakytest.domain.model.RunTrigger
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-flaky-test-hunter")
@PactFolder("../pacts")
@TestSecurity(user = "pact-admin", roles = ["ROLE_ADMIN"])
class FlakyTestTriggerPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @BeforeEach
    fun before(context: PactVerificationContext) {
        val runCheck = mockk<FlakyTestHunterService>()
        coEvery { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL, IDEMPOTENCY_KEY) } returns WORKFLOW_ID
        QuarkusMock.installMockForType(runCheck, FlakyTestHunterService::class.java)
        context.target = HttpTestTarget("localhost", port.toInt())
    }

    @State("an administrator may admit a flaky-test workflow")
    fun administratorMayAdmitWorkflow() = Unit

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun pactVerificationTestTemplate(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "flaky-test-hunter-operator-manual-2026-08-18"
        const val WORKFLOW_ID = "flaky-test-hunter-check-operator_manual-2026-08-18"
    }
}
