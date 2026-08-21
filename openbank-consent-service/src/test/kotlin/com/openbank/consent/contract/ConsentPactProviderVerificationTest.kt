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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
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
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Connection
import javax.sql.DataSource

/**
 * Provider-side Pact verification for consent-service (issue #2255 dimension C3). consent-service is
 * named as provider by consumer pacts in `pacts/` and until now **nothing replayed them** — a
 * committed pact nobody verifies is worthless against the likeliest defect, a request path the
 * provider does not serve (CLAUDE.md "Contract tests"; #2269 is that defect shipping).
 *
 * Git-pact (`@PactFolder`, ADR-0063): reads the consumer pacts from the monorepo-root `pacts/` dir
 * (resolved relative to this module's working directory) and replays each interaction against the
 * running Quarkus test instance. This **always runs** — no broker, no CI secret, no gate — which is
 * exactly why ADR-0063 chose git-pact over a Pact Broker for the verification that must not be
 * skippable. (The broker-based classes elsewhere in the fleet exist for ADR-0092's `can-i-deploy`,
 * a different job; if consent-service is ever added to that gate, extend THIS class rather than
 * adding a second one — see below.)
 *
 * **This must remain the ONLY `@Provider("openbank-consent-service")` class in the repo.** Two
 * classes with the same provider name each pull every pact naming that provider and fight over the
 * same `@BeforeEach` target, so they collide.
 *
 * Authentication: `ConsentResource` is `@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")`
 * at class level. `@TestSecurity` cannot annotate a `@TestTemplate`, so it is applied to the class
 * and Quarkus's test-security extension supplies the identity on every replayed request — matching
 * the production path, where callers present an M2M client-credentials token (ADR-0018). The
 * per-method `@Authorize` checks are advisory here: `authz.enforce` defaults to `false`, so no OPA
 * sidecar is needed for this test (see `application.yaml`).
 *
 * Kafka: the outgoing `consent-events-out` emitter is switched to the in-memory connector, and the
 * outbox dispatcher is disabled — the replayed interactions are reads, so neither is exercised, but
 * a real broker would otherwise be required just to boot.
 *
 * Seeding is done over **JDBC, not the reactive repository**, on purpose: pact-jvm invokes `@State`
 * callbacks by reflection on the bare JUnit thread, which carries no Vert.x context, so a
 * `runBlocking { consentRepository.save(...) }` there throws `No current Vertx context found`
 * (party-service works around this with a duplicated Vert.x context; a plain blocking INSERT is
 * simpler and the schema here is three flat tables).
 *
 * IMPORTANT: if a consumer changes its contract, regenerate the pact JSON on the consumer side and
 * commit it in the same PR, or this test verifies a stale contract.
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact a skip, not a failure.
 */
@QuarkusTest
@QuarkusTestResource(ConsentPactProviderVerificationTest.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-consent-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class ConsentPactProviderVerificationTest {

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
     * State for campaign-service's `CampaignToConsentPactConsumerTest`: the platform do-not-contact
     * read every outbound touch depends on (ADR-0219 D3).
     *
     * This replay is the control that was missing. `GET /api/v1/suppressions/party/{partyId}`
     * answered 500 on EVERY call from the day it shipped, because SuppressionEntity mapped six of
     * its ten columns to names V6 never created (#5711). No unit test could see it — they mock the
     * repository, so no SQL is issued — and campaign's ContactPolicyGate fails closed, so the only
     * symptom was campaigns quietly not sending. Replaying the interaction against a REAL Postgres
     * is what turns that into a red build.
     *
     * The CHECK constraint on `suppressions` requires value IS NULL for scope 'ALL', which is
     * exactly the shape the consumer pins.
     */
    @State("an ALL-scope suppression is active for the pact suppressed party")
    fun activeSuppressionExists() {
        dataSource.connection.use { c ->
            c.autoCommit = false
            // Idempotent: pact-jvm 4.7.3 invokes each @State setup callback twice per interaction.
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

    /**
     * State for campaign-service's live consent check (ADR-0198 D4). The grantee and scope are the
     * ones campaign actually sends — `openbank.campaign.consent-grantee` defaults to
     * `party-service:marketing-comms`, and the scope is MARKETING_COMMS_EMAIL. A state seeded with
     * a plausible-looking grantee would verify a request this consumer never makes.
     */
    @State("an ACTIVE MARKETING_COMMS_EMAIL consent covers the pact consented party")
    fun activeMarketingConsentExists() {
        dataSource.connection.use { c ->
            c.autoCommit = false
            if (!rowExists(c, "SELECT 1 FROM consents WHERE id = ?::uuid", PACT_MARKETING_CONSENT_ID)) {
                c.prepareStatement(INSERT_MARKETING_CONSENT_SQL).use { ps ->
                    ps.setString(1, PACT_MARKETING_CONSENT_ID)
                    ps.setString(2, PACT_CONSENTED_PARTY_ID)
                    ps.setString(3, PACT_MARKETING_GRANTEE_ID)
                    ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO consent_scopes (consent_id, scope) VALUES (?::uuid, ?)").use { ps ->
                    ps.setString(1, PACT_MARKETING_CONSENT_ID)
                    ps.setString(2, "MARKETING_COMMS_EMAIL")
                    ps.executeUpdate()
                }
                c.commit()
            }
        }
    }

    /**
     * State for engagement-service's `EngagementToConsentPactConsumerTest`. Distinct from the
     * campaign one on purpose: engagement gates promotional impressions on MARKETING_COMMS_INAPP,
     * a separate grant from the EMAIL scope campaign's pact pins, so seeding one would not satisfy
     * the other.
     */
    @State("an ACTIVE MARKETING_COMMS_INAPP consent covers the pact engagement party")
    fun activeInAppConsentExists() {
        dataSource.connection.use { c ->
            c.autoCommit = false
            if (!rowExists(c, "SELECT 1 FROM consents WHERE id = ?::uuid", PACT_INAPP_CONSENT_ID)) {
                c.prepareStatement(INSERT_MARKETING_CONSENT_SQL).use { ps ->
                    ps.setString(1, PACT_INAPP_CONSENT_ID)
                    ps.setString(2, PACT_ENGAGEMENT_PARTY_ID)
                    ps.setString(3, PACT_MARKETING_GRANTEE_ID)
                    ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO consent_scopes (consent_id, scope) VALUES (?::uuid, ?)").use { ps ->
                    ps.setString(1, PACT_INAPP_CONSENT_ID)
                    ps.setString(2, "MARKETING_COMMS_INAPP")
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

        /** Must equal `ConsentValidatePactConsumerTest.PACT_CONSENT_ID` (openbank-mcp-service). */
        const val PACT_CONSENT_ID = "c1c1c1c1-d2d2-4e4e-8f8f-a9a9a9a9a9a9"
        const val PACT_PARTY_ID = "c2c2c2c2-d3d3-4e4e-8f8f-a8a8a8a8a8a8"
        const val PACT_GRANTEE_ID = "agent:pact-verify-mcp"
        const val PACT_ACCOUNT_IBAN = "CZ6508000000192000145399"

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

        /** Must equal the ids in openbank-campaign-service's CampaignToConsentPactConsumerTest. */
        const val PACT_SUPPRESSION_ID = "c3c3c3c3-c3c3-4c3c-8c3c-c3c3c3c3c3c3"
        const val PACT_SUPPRESSED_PARTY_ID = "c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1"
        const val PACT_CONSENTED_PARTY_ID = "c2c2c2c2-c2c2-c2c2-c2c2-c2c2c2c2c2c2"
        const val PACT_MARKETING_CONSENT_ID = "c4c4c4c4-c4c4-4c4c-8c4c-c4c4c4c4c4c4"
        const val PACT_MARKETING_GRANTEE_ID = "party-service:marketing-comms"

        /** Must equal the ids in openbank-engagement-service's EngagementToConsentPactConsumerTest. */
        const val PACT_INAPP_CONSENT_ID = "e2e2e2e2-e2e2-4e2e-8e2e-e2e2e2e2e2e2"
        const val PACT_ENGAGEMENT_PARTY_ID = "e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1"
    }
}
