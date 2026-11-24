// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import com.openbank.delegation.application.port.`in`.ResolveMcpClientGrantUseCase
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.security.TestIdentityAssociation
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

abstract class McpClientGrantProviderFixture {
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @Inject
    lateinit var redis: RedisDataSource

    protected fun configureTarget(context: PactVerificationContext?, anonymousStatus: Int) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        // Runs after @TestSecurity installed the class identity; null delegates to the real
        // (token-derived) identity, which with no Authorization header is anonymous.
        if (context?.interaction?.asSynchronousRequestResponse()?.response?.status == anonymousStatus) {
            testIdentity.setTestIdentity(null)
        }
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

    protected fun seedActiveGrant() {
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

    protected fun seedNoGrant() = answer(emptyList())

    /*
     * Payment-proposal create (ADR-0383 D6). Unlike resolve, `PaymentProposalService` reads the
     * grant straight from `DelegationRepository`, so these states seed the REAL table over JDBC —
     * the write paths refuse an MCP_CLIENT grant (dormancy guard), the database does not. Every
     * proposal runs the real service, hash and outbox.
     *
     * Both create interactions carry the same Idempotency-Key and body, so a key left in Redis by
     * the first would replay its answer to the second; each state clears the create keys first.
     * Previous grants for the pact party are revoked rather than deleted (DELETE is not a legal
     * transition), so a fresh grant id is what the create finds and nothing deduplicates.
     */
    protected fun seedActiveProposeGrant() {
        resetProposeState()
        val grant = UUID.randomUUID()
        jdbc().use { c ->
            c.prepareStatement(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, valid_from, status,
                     grantee_type, grantee_client_id, grantee_key_thumbprint)
                values (?, ?, ?, 'ACCOUNT', ?, now() - interval '1 day', 'ACTIVE', 'MCP_CLIENT', ?, ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, grant)
                s.setObject(2, PARTY)
                s.setObject(3, DelegationGrant.mcpClientGranteeId(CLIENT))
                s.setObject(4, ACCOUNT)
                s.setString(5, CLIENT)
                s.setString(6, JKT)
                s.executeUpdate()
            }
            c.prepareStatement(
                "insert into delegation_capabilities (grant_id, capability) values (?, 'ACCOUNT_PROPOSE_PAYMENT')",
            ).use { s ->
                s.setObject(1, grant)
                s.executeUpdate()
            }
        }
    }

    protected fun seedNoProposeGrant() = resetProposeState()

    private fun resetProposeState() {
        val keys = redis.key().keys("*payment-proposal:create:*")
        if (keys.isNotEmpty()) redis.key().del(*keys.toTypedArray())
        jdbc().use { c ->
            c.prepareStatement(
                "update delegation_grants set status = 'REVOKED' where grantor_party_id = ? and status = 'ACTIVE'",
            ).use { s ->
                s.setObject(1, PARTY)
                s.executeUpdate()
            }
        }
    }

    private fun jdbc(): Connection = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    companion object {
        val PARTY: UUID = UUID.fromString("d3d3d3d3-0000-4000-8000-000000000001")
        val GRANT: UUID = UUID.fromString("d3d3d3d3-0000-4000-8000-000000000002")
        val ACCOUNT: UUID = UUID.fromString("d3d3d3d3-0000-4000-8000-000000000003")
        const val CLIENT = "mcp-local-reference"
        const val JKT = "0000000000000000000000000000000000000000000"
    }
}
