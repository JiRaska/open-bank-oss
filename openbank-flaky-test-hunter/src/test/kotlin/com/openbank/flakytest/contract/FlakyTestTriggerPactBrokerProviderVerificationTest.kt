// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-sourced provider verification for flaky-test-hunter — the published-result counterpart to
 * [FlakyTestTriggerPactProviderVerificationTest].
 *
 * `@PactFolder` reads pacts off disk: it never contacts the broker, publishes no verification
 * result and creates no provider version. A provider carrying only that half is invisible to
 * `can-i-deploy`, and a broker version row with zero pacts makes the question *unanswerable*
 * rather than negative — every consumer paired with it resolves `UNVERIFIABLE`. document-service
 * sat in exactly that state for 24 days and blocked three consumers, two of them money-path
 * (#7621, fixed by #7738).
 *
 * Gated on `pactbroker.url`, so it is skipped locally and on the PR lane — `_service-ci.yml`
 * blanks `PACT_BROKER_URL` because the broker has no public ingress (ADR-0056) — and runs on
 * main-push. The `@PactFolder` sibling stays ungated, so PR-time replay (#2327/#2338) is
 * unchanged.
 *
 * The state handlers below are duplicated from the sibling rather than inherited. A subclass was
 * tried first and rejected on evidence: with parent and subclass both present, Quarkus fails with
 * `TestInstantiationException` and the sibling's own tests go red. Duplication is the shape every
 * other pair in the fleet uses.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-flaky-test-hunter")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@TestSecurity(user = "pact-admin", roles = ["ROLE_ADMIN"])
class FlakyTestTriggerPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @BeforeEach
    fun before(context: PactVerificationContext) {
        val runCheck = mockk<FlakyTestHunterService>()
        coEvery { runCheck.startDetached(RunTrigger.OPERATOR_MANUAL) } returns WORKFLOW_ID
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
        const val WORKFLOW_ID = "flaky-test-check-operator_manual-2026-08-18"
    }
}
