// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.context.infrastructure.AmlCaseSourceStatus
import com.openbank.context.integration.ContextMessagingTestResource
import com.openbank.libs.testing.containers.PostgresTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
@TestProfile(IncidentImpactPactProfile::class)
@Provider("openbank-context-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@TestSecurity(user = "pact-operator", roles = ["ROLE_OPERATOR", "ROLE_COMPLIANCE"])
class ContextPactBrokerProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @BeforeEach
    fun target(context: PactVerificationContext) {
        QuarkusMock.installMockForType(
            mockk<AmlCaseSourceStatus> {
                coEvery { isOpen(any()) } returns true
            },
            AmlCaseSourceStatus::class.java,
        )
        context.target = HttpTestTarget("localhost", port.toInt())
    }

    @State("an authorized incident projection is missing")
    fun missing() = IncidentImpactPactFixtures().seed(null)

    @State("an authorized incident projection is available")
    fun available() = IncidentImpactPactFixtures().seed(2)

    @State("an authorized incident projection is partial")
    fun partial() = IncidentImpactPactFixtures().seed(3)

    @State("an incident investigator is unauthorized for another case")
    fun unauthorizedCase() = IncidentImpactPactFixtures().seed(2)

    @State("root-scoped authority history has no recorded evidence")
    fun authorityHistory() = AuthorityHistoryPactFixtures().seed()

    @State("authority history is unauthorized for another root")
    fun unauthorizedAuthorityRoot() = AuthorityHistoryPactFixtures().seed()

    @State("an assigned AML case has open source evidence")
    fun amlEvidence() = AmlCasePactFixtures().seed()

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
