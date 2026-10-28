// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

@QuarkusTest
@TestProfile(CommunicationPactTestProfile::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@Provider("openbank-communication-service")
@PactFolder("../pacts")
class CommunicationPactFolderProviderVerificationTest {
    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "quarkus.http.test-port")
    var testPort: Int = 0

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort)
    }

    @State("customer-copilot has approved published app copy")
    fun publishedCopy() = CommunicationPactStates.configure(dataSource, published = true)

    @State("customer-copilot has no published app copy")
    fun unpublishedCopy() = CommunicationPactStates.configure(dataSource, published = false)

    @Test
    @ExtendWith(NoCommunicationPactContext::class)
    @TestSecurity(user = "copy-viewer", roles = ["ROLE_VIEWER"])
    fun `published copy rejects an identity without an allowed role`() {
        given().get("/api/v1/personas/customer-copilot/published").then().statusCode(403)
    }

    @Test
    @ExtendWith(NoCommunicationPactContext::class)
    @TestSecurity
    fun `published copy requires an authenticated identity`() {
        given().get("/api/v1/personas/customer-copilot/published").then().statusCode(401)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
