// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.devops.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-devops-agent")
@PactBroker(enablePendingPacts = "false")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@TestSecurity(user = "pact-viewer", roles = ["ROLE_VIEWER"])
class DevOpsPactBrokerProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun before(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port.toInt())
    }

    @State("no active DevOps findings exist")
    fun emptyFindings() {
        seedDevOpsPactFindings(dataSource, active = false)
    }

    @State("an active DevOps finding exists")
    fun activeFinding() {
        seedDevOpsPactFindings(dataSource, active = true)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext) {
        context.verifyInteraction()
        given().post("/api/v1/devops/findings/11111111-1111-4111-8111-111111111111/approve")
            .then().statusCode(403)
    }
}
