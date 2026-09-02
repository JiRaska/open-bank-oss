// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Connection
import javax.sql.DataSource

/**
 * Broker-side provider verification for openbank-consent-service, the published-result counterpart to
 * [ConsentPactProviderVerificationTest].
 *
 * WHY BOTH EXIST. A `@PactFolder` test replays the COMMITTED pact from disk: it proves this
 * provider still honours the contract, on every PR, with no infrastructure. It never contacts
 * the broker, so it publishes nothing — and `can-i-deploy` reads published verification
 * results, not green test runs. Without this class the broker never learned that
 * openbank-consent-service verifies anything, so its consumers (openbank-mcp-service) stayed
 * permanently UNVERIFIED and could not be deployed (issue #3232).
 *
 * A second `@Provider("openbank-consent-service")` class is safe here for the reason
 * CLAUDE.md gives for ledger-service's identical pair: the collision it warns about is HTTP vs
 * MESSAGE target dispatch fighting over the same `@BeforeEach`, and both classes here use the
 * same target type, so verifying the same interactions from two pact sources is at worst
 * redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, which have no broker
 * configured. It runs on the main push, where `_service-ci.yml` sets `PUBLISH_RESULTS=true`
 * — that is the run whose result `can-i-deploy` gates the deploy on. The `@PactFolder` class
 * keeps running unconditionally, so PR-time contract coverage is unchanged by this addition.
 */
@QuarkusTest
@QuarkusTestResource(ConsentPactBrokerProviderVerificationTest.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-consent-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class ConsentPactBrokerProviderVerificationTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out").toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // context is null on the @IgnoreNoPactsToVerify dummy invocation — skip gracefully.
        context?.verifyInteraction()
    }

    /**
     * State for mcp-service's `ConsentValidatePactConsumerTest` (ADR-0195): the live-validate call
     * every MCP banking tool fails closed on. Seeds an ACTIVE AISP consent whose grantee, scope and
     * covered IBAN all match the request, so `POST /{id}/validate` answers `valid: true` with the
     * `scopes` / `grantedAccounts` / `frequencyPerDay` projection the consumer reads.
     *
     * `frequency_per_day` is stored but NOT what the response reports — `Consent.frequencyPerDay()`
     * derives 4 from the AISP scope set (PSD2 RTS Art. 10(2)(b)), so the pinned 4 is deterministic
     * regardless of the column. The column is still written because a CHECK constrains it to 1..4.
     */
    @State("an ACTIVE AISP consent covers the pact account for the pact grantee")
    fun activeAispConsentExists() {
        dataSource.connection.use { c ->
            c.autoCommit = false
            // Idempotent: pact-jvm 4.7.3 invokes each @State setup callback twice per interaction,
            // and this inserts with a fixed id.
            if (!consentExists(c)) {
                insertActiveConsent(c)
                insertRow(c, "INSERT INTO consent_scopes (consent_id, scope) VALUES (?::uuid, ?)", "ACCOUNTS_READ")
                insertRow(c, "INSERT INTO consent_accounts (consent_id, iban) VALUES (?::uuid, ?)", PACT_ACCOUNT_IBAN)
                c.commit()
            }
        }
    }

    private fun consentExists(c: Connection): Boolean =
        c.prepareStatement("SELECT 1 FROM consents WHERE id = ?::uuid").use { ps ->
            ps.setString(1, PACT_CONSENT_ID)
            ps.executeQuery().use { rs -> rs.next() }
        }

    private fun insertActiveConsent(c: Connection) = c.prepareStatement(INSERT_CONSENT_SQL).use { ps ->
        ps.setString(1, PACT_CONSENT_ID)
        ps.setString(2, PACT_PARTY_ID)
        ps.setString(3, PACT_GRANTEE_ID)
        ps.executeUpdate()
    }

    /** Child-table insert: both `consent_scopes` and `consent_accounts` are (consent_id, value). */
    private fun insertRow(c: Connection, sql: String, value: String) = c.prepareStatement(sql).use { ps ->
        ps.setString(1, PACT_CONSENT_ID)
        ps.setString(2, value)
        ps.executeUpdate()
    }

    /**
     * States for the two other consumers currently published to the Pact Broker. The broker loader
     * selects every pact naming this provider, not only mcp-service's pact. Keeping these fixtures
     * here is therefore essential: an unhandled state makes the published provider verdict red
     * even when the always-on git-pact replay has the corresponding fixture.
     */
    @State("an ALL-scope suppression is active for the pact suppressed party")
    fun activeSuppressionExists() {
        dataSource.connection.use { c ->
            c.autoCommit = false
            if (!rowExists(c, "SELECT 1 FROM suppressions WHERE id = ?::uuid", PACT_SUPPRESSION_ID)) {
                c.prepareStatement(INSERT_SUPPRESSION_SQL).use { ps ->
                    ps.setString(1, PACT_SUPPRESSION_ID)
                    ps.setString(2, PACT_SUPPRESSED_PARTY_ID)
                    ps.executeUpdate()
                }
                c.commit()
            }
        }
    }

    @State("an ACTIVE MARKETING_COMMS_EMAIL consent covers the pact consented party")
    fun activeMarketingConsentExists() = insertMarketingConsent(
        consentId = PACT_MARKETING_CONSENT_ID,
        partyId = PACT_CONSENTED_PARTY_ID,
        scope = "MARKETING_COMMS_EMAIL",
    )

    @State("an ACTIVE MARKETING_COMMS_INAPP consent covers the pact engagement party")
    fun activeInAppConsentExists() = insertMarketingConsent(
        consentId = PACT_INAPP_CONSENT_ID,
        partyId = PACT_ENGAGEMENT_PARTY_ID,
        scope = "MARKETING_COMMS_INAPP",
    )

    private fun insertMarketingConsent(consentId: String, partyId: String, scope: String) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            if (!rowExists(c, "SELECT 1 FROM consents WHERE id = ?::uuid", consentId)) {
                c.prepareStatement(INSERT_MARKETING_CONSENT_SQL).use { ps ->
                    ps.setString(1, consentId)
                    ps.setString(2, partyId)
                    ps.setString(3, PACT_MARKETING_GRANTEE_ID)
                    ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO consent_scopes (consent_id, scope) VALUES (?::uuid, ?)").use { ps ->
                    ps.setString(1, consentId)
                    ps.setString(2, scope)
                    ps.executeUpdate()
                }
                c.commit()
            }
        }
    }

    private fun rowExists(c: Connection, sql: String, id: String): Boolean = c.prepareStatement(sql).use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs -> rs.next() }
    }

    @State("no consent exists with the pact unknown-consent id")
    fun unknownConsentDoesNotExist() {
        // No setup: a fresh Testcontainer DB satisfies this by construction, and nothing in this
        // class inserts that id. Declared so the state is an explicit part of the contract rather
        // than an unhandled name pact-jvm would pass over silently.
    }

    private companion object {
        private val INSERT_CONSENT_SQL = """
            INSERT INTO consents (
                id, party_id, grantee_id, grantee_type, grantee_name, status,
                valid_from, valid_to, frequency_per_day, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?, 'CUSTOMER_AGENT', 'Pact Verify MCP Agent', 'ACTIVE',
                NOW() - INTERVAL '1 day', NOW() + INTERVAL '30 days', 4, NOW(), NOW()
            )
        """.trimIndent()

        private val INSERT_SUPPRESSION_SQL = """
            INSERT INTO suppressions (
                id, party_id, scope, value, reason_code, source, created_by, created_at
            ) VALUES (
                ?::uuid, ?::uuid, 'ALL', NULL, 'CUSTOMER_OPTOUT', 'preference-centre',
                'pact-operator', NOW()
            )
        """.trimIndent()

        private val INSERT_MARKETING_CONSENT_SQL = """
            INSERT INTO consents (
                id, party_id, grantee_id, grantee_type, grantee_name, status,
                valid_from, valid_to, frequency_per_day, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?, 'CUSTOMER_AGENT', 'Pact Verify Marketing', 'ACTIVE',
                NOW() - INTERVAL '1 day', NOW() + INTERVAL '30 days', 4, NOW(), NOW()
            )
        """.trimIndent()

        /** Must equal `ConsentValidatePactConsumerTest.PACT_CONSENT_ID` (openbank-mcp-service). */
        const val PACT_CONSENT_ID = "c1c1c1c1-d2d2-4e4e-8f8f-a9a9a9a9a9a9"
        const val PACT_PARTY_ID = "c2c2c2c2-d3d3-4e4e-8f8f-a8a8a8a8a8a8"
        const val PACT_GRANTEE_ID = "agent:pact-verify-mcp"
        const val PACT_ACCOUNT_IBAN = "CZ6508000000192000145399"
        const val PACT_SUPPRESSION_ID = "c3c3c3c3-c3c3-4c3c-8c3c-c3c3c3c3c3c3"
        const val PACT_SUPPRESSED_PARTY_ID = "c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1"
        const val PACT_CONSENTED_PARTY_ID = "c2c2c2c2-c2c2-c2c2-c2c2-c2c2c2c2c2c2"
        const val PACT_MARKETING_CONSENT_ID = "c4c4c4c4-c4c4-4c4c-8c4c-c4c4c4c4c4c4"
        const val PACT_MARKETING_GRANTEE_ID = "party-service:marketing-comms"
        const val PACT_INAPP_CONSENT_ID = "e2e2e2e2-e2e2-4e2e-8e2e-e2e2e2e2e2e2"
        const val PACT_ENGAGEMENT_PARTY_ID = "e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1"
    }
}
