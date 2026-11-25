// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalPayload
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

abstract class DelegationProposalProviderFixture {
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    protected fun seed(status: String) {
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
