// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.communication.it.CommunicationPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(CommunicationPostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@Provider("openbank-communication-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class CommunicationPactBrokerProviderVerificationTest {
    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "quarkus.http.test-port")
    var testPort: Int = 0

    @BeforeEach
    fun target(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", testPort)
    }

    @State("customer-copilot has approved published app copy")
    fun publishedCopy() = CommunicationPactStates.configure(dataSource, published = true)

    @State("customer-copilot has no published app copy")
    fun unpublishedCopy() = CommunicationPactStates.configure(dataSource, published = false)

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
