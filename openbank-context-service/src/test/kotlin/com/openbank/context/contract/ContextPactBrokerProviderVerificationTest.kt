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
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.util.UUID

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
@TestSecurity(user = "pact-operator", roles = ["ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_CREDIT_RISK"])
class ContextPactBrokerProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

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

    @State("a lending investigator is assigned to the requested loan")
    fun lendingLoanAssignment() {
        val config = org.eclipse.microprofile.config.ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM context_case_assignments WHERE principal_id = 'pact-operator'")
                statement.executeUpdate(
                    """INSERT INTO context_case_assignments
                        (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref, valid_from, valid_to, created_at)
                        VALUES ('${UUID.randomUUID()}', 'openbank-cz', 'pact-operator', '$LENDING_LOAN_ID',
                        'LENDING_EXPOSURE_REVIEW', 'lending-loan:$LENDING_LOAN_ID',
                        now() - interval '1 hour', now() + interval '1 hour', now())
                    """.trimIndent(),
                )
            }
        }
    }

    @State("a lending investigator is not assigned to the requested loan")
    fun noLendingLoanAssignment() {
        val config = org.eclipse.microprofile.config.ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "DELETE FROM context_case_assignments WHERE principal_id = 'pact-operator' AND case_id = '$LENDING_LOAN_ID'",
                )
            }
        }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext) {
        if (context.interaction.description == "GET assigned lending loan candidates without a shared match") {
            val principal = mockk<JsonWebToken> {
                every { name } returns "pact-operator"
                every { rawToken } returns "pact-token"
            }
            testIdentityAssociation.setTestIdentity(
                QuarkusSecurityIdentity.builder().setPrincipal(principal)
                    .addRoles(setOf("ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_CREDIT_RISK")).build(),
            )
        }
        context.verifyInteraction()
    }

    private companion object {
        const val LENDING_LOAN_ID = "f0c8d9e0-0000-4000-8000-000000000003"
    }
}
