// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.context.integration.ContextMessagingTestResource
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
@TestProfile(IncidentImpactPactProfile::class)
@Provider("openbank-context-service")
@PactFolder("../pacts")
@TestSecurity(user = "pact-operator", roles = ["ROLE_OPERATOR"])
class ContextPactProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @BeforeEach
    fun target(context: PactVerificationContext) {
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

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
