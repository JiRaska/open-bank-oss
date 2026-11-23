// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalPayload
import com.openbank.delegation.infrastructure.client.RestResourceOwnershipClient
import com.openbank.delegation.it.PostgresTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ADR-0383 D6 over real HTTP + PostgreSQL: who may create, who may decide, and that an approval
 * releases only the exact instruction, once, while the proposal is live and its grant ACTIVE.
 */
@QuarkusTest
@QuarkusTestResource(PaymentProposalApiIT.InMemoryKafka::class)
@QuarkusTestResource(PostgresTestResource::class)
class PaymentProposalApiIT {

    class InMemoryKafka : io.quarkus.test.common.QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            io.smallrye.reactive.messaging.memory.InMemoryConnector.switchOutgoingChannelsToInMemory(
                "delegation-events-out",
                "payment-proposal-events-out",
            ) + mapOf(
                "openbank.outbox.dispatch-enabled" to "false",
                "openbank.delegation.mcp-connect-enabled" to "true",
            )

        override fun stop() = io.smallrye.reactive.messaging.memory.InMemoryConnector.clear()
    }

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    // ---- mcp-service: create only ----

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = SUB),
            Claim(key = "azp", value = "openbank-mcp"),
            Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `create requires an Idempotency-Key and replays the first answer for it`() {
        seedGrant()
        // A complete, valid request with NO key: only the key requirement can refuse it.
        create(key = "").statusCode(400)
        assertThat(proposalCount()).isZero()
        val key = UUID.randomUUID().toString()
        val first = create(key = key).statusCode(201).extract().path<String>("proposalId")
        create(key = key).statusCode(201).header("X-Idempotency-Replayed", equalTo("true"))
            .body("proposalId", equalTo(first))
        create(key = key, name = "Someone Else").statusCode(409)
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `mcp creates a PENDING proposal under an active grant, deduplicates, and records an event`() {
        seedGrant()
        val first = create().statusCode(201).body("status", equalTo("PENDING")).extract().path<String>("proposalId")
        create().statusCode(200).body("deduplicated", equalTo(true)).body("proposalId", equalTo(first))
        assertThat(rowStatus(UUID.fromString(first))).isEqualTo("PENDING")
        assertThat(outboxCount(UUID.fromString(first), "PaymentProposalCreated")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `mcp cannot create without a grant for that client, key and account`() {
        seedGrant()
        create(client = "another-client").statusCode(403)
        create(jkt = "x".repeat(43)).statusCode(403)
        create(debtor = UUID.randomUUID()).statusCode(403)
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `mcp cannot read, approve, reject or record execution`() {
        val id = seedProposal()
        val hash = hashOf(id)
        given().get("/api/v1/payment-proposals/$id?partyId=$party").then().statusCode(403)
        decide(id, "approve", hash, party).statusCode(403)
        decide(id, "reject", hash, party).statusCode(403)
        decide(id, "execution", hash, party).statusCode(403)
        assertThat(rowStatus(id)).isEqualTo("PENDING")
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `the free text cannot carry a bidi override`() {
        seedGrant()
        create(name = "Pay‮eman").statusCode(400)
    }

    // ---- customer-edge: read + decide only ----

    @Test
    @TestSecurity(user = "service-account-unrelated", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = SUB),
            Claim(key = "azp", value = "unrelated"),
            Claim(key = "preferred_username", value = "service-account-unrelated"),
        ],
    )
    fun `operator role alone cannot inspect or decide a customer proposal`() {
        val id = seedProposal()
        given().header(HDR, party).get("/api/v1/payment-proposals/$id?partyId=$party").then().statusCode(403)
        decide(id, "approve", hashOf(id), party).statusCode(403)
        assertThat(rowStatus(id)).isEqualTo("PENDING")
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `edge cannot create a proposal`() {
        seedGrant()
        create().statusCode(403)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `approve once with the exact hash, retry idempotent, approval after execution refused`() {
        val id = seedProposal()
        val hash = hashOf(id)
        given().header(HDR, party).get("/api/v1/payment-proposals?partyId=$party").then().statusCode(200)
            .body("[0].payloadSha256", equalTo(hash))
            .body("[0].approvalRequestId", equalTo("mcp-proposal:$id"))
        decide(id, "approve", hash, party).statusCode(200).body("status", equalTo("APPROVED"))
        // A retry before execution is idempotent: no new state and no second Approved event.
        decide(id, "approve", hash, party).statusCode(200).body("status", equalTo("APPROVED"))
        decide(id, "execution", "0".repeat(64), party, UUID.randomUUID()).statusCode(409)
        decide(id, "execution", hash, party, UUID.randomUUID()).statusCode(200).body("status", equalTo("EXECUTED"))
        decide(id, "approve", hash, party).statusCode(409) // a second approval after success is refused
        assertThat(outboxCount(id, "PaymentProposalApproved")).isEqualTo(1)
        assertThat(outboxCount(id, "PaymentProposalExecuted")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `approval with a mismatched hash is refused and leaves the proposal PENDING`() {
        val id = seedProposal()
        decide(id, "approve", "a".repeat(64), party).statusCode(409).body("code", equalTo("PROPOSAL_HASH_MISMATCH"))
        assertThat(rowStatus(id)).isEqualTo("PENDING")
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `an expired proposal is refused`() {
        val id = seedProposal(createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        decide(id, "approve", hashOf(id), party).statusCode(409)
        assertThat(rowStatus(id)).isEqualTo("PENDING") // reads as EXPIRED; nothing was approved
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `another party's proposal is invisible and cannot be decided`() {
        val id = seedProposal()
        val other = UUID.randomUUID()
        given().header(HDR, other).get("/api/v1/payment-proposals/$id?partyId=$other").then().statusCode(404)
        decide(id, "approve", hashOf(id), other).statusCode(404)
        // and the party header must match the claimed party
        given().header(HDR, other).get("/api/v1/payment-proposals/$id?partyId=$party").then().statusCode(403)
        assertThat(rowStatus(id)).isEqualTo("PENDING")
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `a proposal whose grant was revoked is rejected, not approvable`() {
        val id = seedProposal()
        jdbc().use {
            it.createStatement().executeUpdate(
                "update delegation_grants set status = 'REVOKED' where grantor_party_id = '$party'",
            )
        }
        decide(id, "approve", hashOf(id), party).statusCode(409)
        assertThat(rowStatus(id)).isEqualTo("REJECTED")
        assertThat(outboxCount(id, "PaymentProposalRejected")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `a retry is refused once the grant is no longer active`() {
        val id = seedProposal()
        decide(id, "approve", hashOf(id), party).statusCode(200)
        jdbc().use {
            it.createStatement().executeUpdate(
                "update delegation_grants set status = 'REVOKED' where grantor_party_id = '$party'",
            )
        }
        decide(id, "approve", hashOf(id), party).statusCode(409)
    }

    @Test
    fun `the proposal table refuses TRUNCATE and DELETE`() {
        seedProposal()
        jdbc().use { c ->
            org.assertj.core.api.Assertions.assertThatThrownBy {
                c.createStatement().execute("truncate delegation_payment_proposals")
            }.hasMessageContaining("append-only")
            org.assertj.core.api.Assertions.assertThatThrownBy {
                c.createStatement().execute("delete from delegation_payment_proposals where party_id = '$party'")
            }.hasMessageContaining("append-only")
        }
    }

    // ---- the grant's own write path ----

    @Inject
    lateinit var grants: com.openbank.delegation.infrastructure.persistence.repository.DelegationRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T = io.quarkus.vertx.VertxContextSupport.subscribeAndAwait {
        io.smallrye.mutiny.coroutines.uni(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        ) {
            block()
        }
    }

    /** Any event: this drives the repository's revoke write, not the service's event builder. */
    private class TestGrantEvent(override val aggregateId: UUID) :
        com.openbank.libs.domain.event.DomainEvent(Instant.now()) {
        override val aggregateType = "DelegationGrant"
        override val eventType = "TestGrantClosed"
        override val version = 1L
    }

    @Test
    fun `revoking the grant rejects its pending proposals in the same write, even a malformed row`() {
        val good = seedProposal()
        val grant = grantOf(good)
        val malformed = seedProposal(grant = grant, hashOverride = "0".repeat(64)) // fails domain load
        val revoked = onEventLoop { grants.findById(grant) }!!
            .revoke(party, "customer disconnected the client", java.time.OffsetDateTime.now())
        onEventLoop { grants.save(revoked, TestGrantEvent(grant)) }
        assertThat(rowStatus(good)).isEqualTo("REJECTED")
        assertThat(rowStatus(malformed)).isEqualTo("REJECTED")
        assertThat(outboxCount(malformed, "PaymentProposalRejected")).isEqualTo(1)
        assertThat(grantStatus(grant)).isEqualTo("REVOKED")
    }

    private fun grantOf(proposal: UUID): UUID = jdbc().use { c ->
        c.createStatement().executeQuery("select grant_id from delegation_payment_proposals where id = '$proposal'")
            .use {
                it.next()
                UUID.fromString(it.getString(1))
            }
    }

    private fun grantStatus(grant: UUID): String = jdbc().use { c ->
        c.createStatement().executeQuery("select status from delegation_grants where id = '$grant'")
            .use {
                it.next()
                it.getString(1)
            }
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = SUB),
            Claim(key = "azp", value = "openbank-mcp"),
            Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `V2 create and cached receipt require the exact live connection and current ownership`() {
        val ownership = mockk<RestResourceOwnershipClient>()
        coEvery { ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, account) } returns
            OwnershipVerdict.OWNED
        QuarkusMock.installMockForType(ownership, RestResourceOwnershipClient::class.java)
        val connection = seedConnection()
        val targetGrant = seedGrant(connection)
        val otherConnection = seedConnection()
        val key = UUID.randomUUID().toString()

        create(key = key).statusCode(403)
        seedGrant()
        create(key = key, connectionId = otherConnection).statusCode(403)
        val id = create(key = key, connectionId = connection).statusCode(201).body("status", equalTo("PENDING"))
            .extract().path<String>("proposalId")
        assertThat(grantOf(UUID.fromString(id))).isEqualTo(targetGrant)
        create(key = key, connectionId = connection).statusCode(201).header("X-Idempotency-Replayed", equalTo("true"))
        coEvery {
            ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, account)
        } returns OwnershipVerdict.NOT_OWNED
        create(key = key, connectionId = connection).statusCode(403)
        coEvery { ownership.verifyOwnership(party, DelegationResourceType.ACCOUNT, account) } returns
            OwnershipVerdict.OWNED
        jdbc().use {
            it.createStatement().executeUpdate(
                "update delegation_grants set status = 'REVOKED' where id = '$targetGrant'",
            )
        }
        create(key = key, connectionId = connection).statusCode(403)
    }

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = SUB),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `V2 revoke closes only its pending proposal and writes one lifecycle event`() {
        val connection = seedConnection()
        val grant = seedGrant(connection)
        val target = seedProposal(grant = grant)
        val other = seedProposal(grant = seedGrant(seedConnection()))
        val path = "/api/v1/delegations/mcp-connect/customer/connections/$connection/revoke"
        fun revoke(asParty: UUID) = given().contentType(ContentType.JSON).header(HDR, asParty)
            .body("{}").post(path).then()
        revoke(UUID.randomUUID()).statusCode(404)
        revoke(party).statusCode(204)
        revoke(party).statusCode(204)
        assertThat(grantStatus(grant)).isEqualTo("REVOKED")
        assertThat(rowStatus(target)).isEqualTo("REJECTED")
        assertThat(rowStatus(other)).isEqualTo("PENDING")
        assertThat(outboxCount(target, "PaymentProposalRejected")).isEqualTo(1)
    }

    // ---- helpers ----

    private fun create(
        client: String = CLIENT,
        jkt: String = JKT,
        debtor: UUID = account,
        name: String = "Elektrárna Praha a.s.",
        key: String = UUID.randomUUID().toString(),
        connectionId: UUID? = null,
    ) = given().contentType(ContentType.JSON).body(
        mapOf(
            "partyId" to party.toString(), "mcpClientId" to client, "keyThumbprint" to jkt,
            "operationType" to "DOMESTIC_PAYMENT", "debtorAccountId" to debtor.toString(),
            "creditorIban" to IBAN, "creditorName" to name, "amount" to "1250.50", "currency" to "CZK",
        ) + (connectionId?.let { mapOf("connectionId" to it.toString()) } ?: emptyMap()),
    ).header("Idempotency-Key", key).post("/api/v1/payment-proposals").then()

    private fun decide(id: UUID, verb: String, hash: String, asParty: UUID, paymentId: UUID? = null) =
        given().contentType(
            ContentType.JSON,
        ).header(HDR, asParty).header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                mapOf("partyId" to asParty.toString(), "payloadSha256" to hash) +
                    (paymentId?.let { mapOf("paymentId" to it.toString()) } ?: emptyMap()),
            )
            .post("/api/v1/payment-proposals/$id/$verb").then()

    private fun payload() = PaymentProposalPayload(
        PaymentProposalOperationType.DOMESTIC_PAYMENT,
        account,
        IBAN,
        "Elektrárna Praha a.s.",
        BigDecimal("1250.50"),
        "CZK",
    )

    private fun hashOf(id: UUID) = PaymentProposal.canonicalSha256(id, party, payload())

    private fun jdbc(): Connection = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun seedGrant(connectionId: UUID? = null): UUID {
        val id = UUID.randomUUID()
        jdbc().use { c ->
            c.createStatement().executeUpdate(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, valid_from, status,
                     grantee_type, grantee_client_id, grantee_key_thumbprint, mcp_connection_id)
                values ('$id', '$party', '${DelegationGrant.mcpClientGranteeId(
                    CLIENT,
                )}', 'ACCOUNT', '$account', now() - interval '1 day', 'ACTIVE',
                        'MCP_CLIENT', '$CLIENT', '$JKT', ${connectionId?.let { "'$it'" } ?: "NULL"})
                """.trimIndent(),
            )
            c.createStatement().executeUpdate(
                "insert into delegation_capabilities (grant_id, capability) values ('$id', 'ACCOUNT_PROPOSE_PAYMENT')",
            )
        }
        return id
    }

    private fun seedConnection(): UUID {
        val connection = UUID.randomUUID()
        fun reference() = UUID.randomUUID().toString().replace("-", "").repeat(2)
        jdbc().use { c ->
            c.createStatement().executeUpdate(
                """
                insert into mcp_connect_requests
                    (request_ref, party_id, client_id, par_jkt, redirect_host, resource, scopes, expires_at,
                     challenge_id, context_sha256, code_binding_ref, staged_at, refresh_family_ref,
                     connection_id, activated_at, grant_valid_until)
                values ('${reference()}', '$party', '$CLIENT', '$JKT', '127.0.0.1', 'https://mcp.test/customer/mcp',
                        array['openid'], now() + interval '1 minute', '${UUID.randomUUID()}', '${reference()}',
                        '${reference()}', now() - interval '1 minute', '${reference()}', '$connection',
                        now() - interval '30 seconds', now() + interval '1 day')
                """.trimIndent(),
            )
        }
        return connection
    }

    private fun seedProposal(
        createdAt: Instant = Instant.now(),
        grant: UUID = seedGrant(),
        hashOverride: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc().use { c ->
            c.prepareStatement(
                """
                insert into delegation_payment_proposals
                    (id, party_id, grant_id, mcp_client_id, operation_type, debtor_account_id, creditor_iban,
                     creditor_name, amount, currency, payload_sha256, status, created_at, expires_at)
                values (?, ?, ?, ?, 'DOMESTIC_PAYMENT', ?, ?, ?, 1250.50, 'CZK', ?, 'PENDING', ?, ?)
                """.trimIndent(),
            ).use { s ->
                s.setObject(1, id)
                s.setObject(2, party)
                s.setObject(3, grant)
                s.setString(4, CLIENT)
                s.setObject(5, account)
                s.setString(6, IBAN)
                s.setString(7, "Elektrárna Praha a.s.")
                s.setString(8, hashOverride ?: hashOf(id))
                s.setTimestamp(9, Timestamp.from(createdAt))
                s.setTimestamp(10, Timestamp.from(createdAt.plus(24, ChronoUnit.HOURS)))
                s.executeUpdate()
            }
        }
        return id
    }

    private fun proposalCount(): Int = jdbc().use { c ->
        c.createStatement().executeQuery("select count(*) from delegation_payment_proposals where party_id = '$party'")
            .use {
                it.next()
                it.getInt(1)
            }
    }

    private fun rowStatus(id: UUID): String = jdbc().use { c ->
        c.createStatement().executeQuery("select status from delegation_payment_proposals where id = '$id'").use {
            it.next()
            it.getString(1)
        }
    }

    private fun outboxCount(id: UUID, type: String): Int = jdbc().use { c ->
        c.createStatement().executeQuery(
            "select count(*) from delegation_outbox where aggregate_id = '$id' and event_type = '$type'",
        ).use {
            it.next()
            it.getInt(1)
        }
    }

    private companion object {
        const val MCP = "service-account-openbank-mcp"
        const val EDGE = "service-account-openbank-edge"
        const val SUB = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"
        const val HDR = "X-Customer-Party-Id"
        const val CLIENT = "mcp-hosted-reference"
        const val JKT = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"
        const val IBAN = "CZ6508000000192000145399"
    }
}
