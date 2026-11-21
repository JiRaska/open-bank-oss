// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.delegation.application.port.`in`.ResolveMcpClientGrantUseCase
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.integration.SpendReservationOutboxWriteIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PR-time replay of mcp-service's HTTP pact against the REAL resource (ADR-0383 D3,
 * `POST /api/v1/delegations/mcp-client/resolve`): routing, the in-code identity guard, request
 * validation and the JSON shape all run; only the use case behind the port is substituted per
 * provider state (MCP_CLIENT grants cannot be persisted yet — the ADR-0383 dormancy guard).
 *
 * `@Consumer` scopes this class to mcp-service's pact; the event pacts stay with
 * [DelegationEventPactFolderProviderVerificationTest], which skips HTTP interactions.
 * The caller is presented as mcp-service's own verified service-account (azp + preferred_username),
 * the only identity the handler admits. The negative half (a non-mcp caller is refused 403) lives
 * in [McpClientGrantIdentityGuardIT]: a plain `@Test` here cannot resolve the
 * [PactVerificationContext] the `@BeforeEach` takes.
 */
@QuarkusTest
@TestProfile(DelegationMcpClientGrantPactFolderProviderVerificationTest.ResolverDouble::class)
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-mcp", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
        Claim(key = "azp", value = "openbank-mcp"),
        Claim(key = "preferred_username", value = "service-account-openbank-mcp"),
    ],
)
@Provider("openbank-delegation-service")
@Consumer("openbank-mcp-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DelegationMcpClientGrantPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /** Enables [FakeResolver] for this class only (a Kotlin bean class is final, so no QuarkusMock). */
    class ResolverDouble : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakeResolver::class.java)
    }

    @Alternative
    @ApplicationScoped
    class FakeResolver : ResolveMcpClientGrantUseCase {
        override suspend fun resolve(grantorPartyId: UUID, clientId: String, keyThumbprint: String) =
            if (grantorPartyId == PARTY && clientId == CLIENT && keyThumbprint == JKT) answer else emptyList()

        companion object {
            @Volatile
            var answer: List<DelegationGrant> = emptyList()
        }
    }

    private fun answer(grants: List<DelegationGrant>) {
        FakeResolver.answer = grants
    }

    @State("an active MCP_CLIENT grant exists for the pact party, client and key")
    fun activeGrant() {
        val now = OffsetDateTime.now()
        answer(
            listOf(
                DelegationGrant(
                    id = GRANT,
                    grantorPartyId = PARTY,
                    granteePartyId = DelegationGrant.mcpClientGranteeId(CLIENT),
                    granteeType = DelegationGranteeType.MCP_CLIENT,
                    granteeClientId = CLIENT,
                    granteeKeyThumbprint = JKT,
                    resourceType = DelegationResourceType.ACCOUNT,
                    resourceId = ACCOUNT,
                    capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
                    validFrom = now.minusDays(1),
                    validTo = now.plusDays(30),
                    status = DelegationStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
    }

    @State("no MCP_CLIENT grant exists for the pact party, client and key")
    fun noGrant() = answer(emptyList())

    companion object {
        val PARTY: UUID = UUID.fromString("d3d3d3d3-0000-4000-8000-000000000001")
        val GRANT: UUID = UUID.fromString("d3d3d3d3-0000-4000-8000-000000000002")
        val ACCOUNT: UUID = UUID.fromString("d3d3d3d3-0000-4000-8000-000000000003")
        const val CLIENT = "mcp-local-reference"
        const val JKT = "0000000000000000000000000000000000000000000"
    }
}
