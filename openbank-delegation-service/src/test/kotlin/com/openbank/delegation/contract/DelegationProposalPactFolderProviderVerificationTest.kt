// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalPayload
import com.openbank.delegation.integration.PaymentProposalApiIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Git-pact HTTP provider replay for the payment-proposal routes customer-edge calls (ADR-0383 D6):
 * read, approve, record execution, reject. Runs on every PR (`check-pact-provider-replay.py`).
 *
 * ## Why a second `@PactFolder` class, split by `@PactFilter`
 *
 * [DelegationEventPactFolderProviderVerificationTest] is a plain-JVM message replay; these
 * interactions need the real HTTP stack, PostgreSQL and the service-account identity guard. The
 * two classes partition the folder by provider-state name — this one takes every state naming a
 * "payment proposal", the message classes take every other — the same split account-service uses
 * (`AccountNegativeAuthProviderVerificationTest`), so no interaction is replayed twice and none is
 * skipped: a new HTTP interaction without a proposal state lands on the message target and fails
 * loudly rather than silently.
 *
 * ## Identity
 *
 * The routes admit only `service-account-openbank-edge` acting for the customer named in
 * `X-Customer-Party-Id`; the class-level identity is exactly that, so the replay goes through the
 * same guard production does.
 *
 * ## Negative half
 *
 * The pact carries the happy path only. The refusals — a caller that is not the edge service account
 * (403), a proposal id that belongs to another customer (404, enumeration resistance) and a wrong
 * payload hash — are asserted against the same routes in [PaymentProposalApiIT]; a plain `@Test`
 * here cannot resolve the `PactVerificationContext` the `@BeforeEach` takes.
 *
 * ## Seeding a fixed id into an append-only table
 *
 * The contract pins the proposal id (the payload hash covers it), and the table forbids DELETE by
 * trigger. Each state handler therefore removes the previous replay's row in a session with
 * `session_replication_role = replica` — test database only — and inserts a fresh one.
 */
@QuarkusTest
@QuarkusTestResource(PaymentProposalApiIT.InMemoryKafka::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = DelegationProposalPactFolderProviderVerificationTest.EDGE, roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = DelegationProposalPactFolderProviderVerificationTest.EDGE),
    ],
)
@Provider("openbank-delegation-service")
@PactFolder("../pacts")
@PactFilter(DelegationProposalPactFolderProviderVerificationTest.PROPOSAL_STATES)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DelegationProposalPactFolderProviderVerificationTest {

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

    @State("a PENDING payment proposal exists for the customer")
    fun pendingProposal() = seed("PENDING")

    @State("an APPROVED payment proposal exists for the customer")
    fun approvedProposal() = seed("APPROVED")

    private fun seed(status: String) {
        val now = Instant.now()
        jdbc().use { c ->
            c.createStatement().use { s ->
                s.execute("set session_replication_role = replica")
                s.executeUpdate("delete from delegation_outbox where aggregate_id = '$PROPOSAL'")
                s.executeUpdate("delete from delegation_payment_proposals where id = '$PROPOSAL'")
                s.execute("set session_replication_role = origin")
                s.executeUpdate(
                    """
                    insert into delegation_grants
                        (id, grantor_party_id, grantee_party_id, resource_type, resource_id, valid_from, status,
                         grantee_type, grantee_client_id, grantee_key_thumbprint)
                    values ('$GRANT', '$PARTY', '${DelegationGrant.mcpClientGranteeId(CLIENT)}', 'ACCOUNT',
                            '$ACCOUNT', now() - interval '1 day', 'ACTIVE', 'MCP_CLIENT', '$CLIENT', '$JKT')
                    on conflict (id) do nothing
                    """.trimIndent(),
                )
                s.executeUpdate(
                    "insert into delegation_capabilities (grant_id, capability) " +
                        "select '$GRANT', 'ACCOUNT_PROPOSE_PAYMENT' where not exists " +
                        "(select 1 from delegation_capabilities where grant_id = '$GRANT')",
                )
            }
            c.prepareStatement(
                """
                insert into delegation_payment_proposals
                    (id, party_id, grant_id, mcp_client_id, operation_type, debtor_account_id, creditor_iban,
                     creditor_name, amount, currency, payload_sha256, status, created_at, expires_at, decided_at)
                values (?, ?, ?, ?, 'DOMESTIC_PAYMENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, PROPOSAL)
                s.setObject(2, PARTY)
                s.setObject(3, GRANT)
                s.setString(4, CLIENT)
                s.setObject(5, ACCOUNT)
                s.setString(6, IBAN)
                s.setString(7, CREDITOR)
                s.setBigDecimal(8, BigDecimal(AMOUNT))
                s.setString(9, CURRENCY)
                s.setString(10, hash())
                s.setString(11, status)
                s.setTimestamp(12, Timestamp.from(now))
                s.setTimestamp(13, Timestamp.from(now.plus(24, ChronoUnit.HOURS)))
                s.setTimestamp(14, if (status == "PENDING") null else Timestamp.from(now))
                s.executeUpdate()
            }
        }
    }

    /** The provider's OWN canonical hash; the pact pins the consumer's, so the replay compares the two. */
    private fun hash() = PaymentProposal.canonicalSha256(
        PROPOSAL,
        PARTY,
        PaymentProposalPayload(
            PaymentProposalOperationType.DOMESTIC_PAYMENT,
            ACCOUNT,
            IBAN,
            CREDITOR,
            BigDecimal(AMOUNT),
            CURRENCY,
        ),
    )

    private fun jdbc(): Connection = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    companion object {
        const val EDGE = "service-account-openbank-edge"

        /** Every provider state naming a payment proposal; the message replays take the complement. */
        const val PROPOSAL_STATES = ".*payment proposal.*"

        // Fixed values — must match customer-edge's CustomerEdgeDelegationProposalPactConsumerTest.
        val PROPOSAL: UUID = UUID.fromString("c0ffee00-1111-4222-8333-444455556666")
        val PARTY: UUID = UUID.fromString("c0ffee00-2222-4333-8444-555566667777")
        val ACCOUNT: UUID = UUID.fromString("c0ffee00-3333-4444-8555-666677778888")
        val GRANT: UUID = UUID.fromString("c0ffee00-6666-4777-8888-9999aaaabbbb")
        const val CLIENT = "mcp-hosted-reference"
        const val JKT = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"
        const val IBAN = "CZ6508000000192000145399"
        const val CREDITOR = "Elektrarna Praha a.s."
        const val AMOUNT = "1250.50"
        const val CURRENCY = "CZK"
    }
}
