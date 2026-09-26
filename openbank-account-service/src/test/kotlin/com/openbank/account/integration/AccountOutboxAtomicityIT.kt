// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.it.PostgresRedpandaRedisTestResource
import com.openbank.account.it.StubScaChallengeClient
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `WithdrawalProposalRepositoryImpl.save(proposal, event)` commits the
 * `savings_withdrawal_proposals` row and its `account_outbox` row in **one** database transaction,
 * so neither can exist without the other.
 *
 * The subject is the owner's SCA-bound APPROVAL of a delegate's savings-withdrawal proposal
 * (`POST .../proposals/{id}/decide` with `approve: true`) — the only call site in this service that
 * writes the aggregate and an outbox event together. The sibling REJECT branch deliberately writes
 * no outbox row at all: `save(proposal)` without an event, which this test uses as a control.
 *
 * ### Why presence is not the property
 *
 * The house pattern (`LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`) drives the flow through
 * the real REST endpoint and asserts the outbox row landed. That is necessary — a mocked repository
 * commits nothing, and a reactive Panache repo cannot be called from a bare `@QuarkusTest` thread
 * ("No current Vertx context found"), so only a real HTTP request can exercise the write — but it
 * is **not sufficient**: an implementation that persisted the aggregate in one transaction and the
 * outbox row in a second would satisfy every presence assertion while having lost the property.
 * The sibling [SavingsProposalIT] is in exactly that position — it asserts the decision returned
 * `APPROVED`, which a two-transaction save answers identically.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Comparing the two `xmin`s is a direct read of the property rather than of its happy-path shadow —
 * splitting `save`'s single `Panache.withTransaction` in two turns this test red, where a presence
 * assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`, which would race the assertion. (This
 * service correctly ships `openbank.outbox.dispatch-enabled: true` in `application.yaml` — the
 * fleet default is `false`, under which a service dispatches nothing and reports no error — which
 * is precisely why it has to be disabled here rather than merely left alone.)
 *
 * That switch lives in a [QuarkusTestProfile] and NOT in the test resource, deliberately: a
 * `@QuarkusTestResource` is applied to every test class in the module, so a resource carrying
 * `dispatch-enabled=false` would silently disable dispatch for the module's other ITs too —
 * measured in the sdd half of this change, where doing so turned that module's dispatch IT red. A
 * profile is per-class and forces this class its own Quarkus boot, which is exactly the scope
 * wanted.
 */
@QuarkusTest
@TestProfile(AccountOutboxAtomicityIT.NoDispatchProfile::class)
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@QuarkusTestResource(AccountOutboxAtomicityIT.DelegationChannel::class)
class AccountOutboxAtomicityIT {

    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
    }

    class DelegationChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("delegation-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: DataSource

    private val ownerParty: UUID = UUID.randomUUID()
    private val delegateParty: UUID = UUID.randomUUID()

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `approving a proposal commits the proposal row and its outbox row in one transaction`(): Unit = runBlocking {
        val accountId = openAccount()
        grantSavingsDelegation(accountId)

        val approved = approve(accountId, propose(accountId))
        val rows = writersOf(approved)

        assertThat(rows)
            .describedAs("exactly one account_outbox row for proposal %s", approved)
            .hasSize(1)
        val (proposalXmin, outboxXmin, eventType) = rows.single()
        assertThat(eventType).isEqualTo(EVENT_WITHDRAWAL_APPROVED)
        assertThat(outboxXmin)
            .describedAs(
                "the savings_withdrawal_proposals row and its outbox row must carry the SAME " +
                    "Postgres xmin — different values mean two transactions wrote them, so one can " +
                    "commit without the other (proposal xmin=%s, outbox xmin=%s)",
                proposalXmin,
                outboxXmin,
            )
            .isEqualTo(proposalXmin)

        // Known-different control, so one run shows the identical comparison both matching and NOT
        // matching. A second approval on the same account is a second request and therefore a
        // second transaction; if its outbox row shared a writer with the first, the match above
        // would be matching everything and could not fail.
        val second = approve(accountId, propose(accountId))
        val secondRows = writersOf(second)
        assertThat(secondRows).hasSize(1)
        assertThat(secondRows.single().outboxXmin).isEqualTo(secondRows.single().proposalXmin)
        assertThat(secondRows.single().outboxXmin)
            .describedAs(
                "control: two separate approvals cannot share a writing transaction (first=%s, second=%s)",
                outboxXmin,
                secondRows.single().outboxXmin,
            )
            .isNotEqualTo(outboxXmin)
    }

    /**
     * The REJECT branch calls `save(proposal)` — the overload with no event — so it writes no
     * outbox row. Asserting that here keeps the `hasSize(1)` above honest about *which* write path
     * produced the row: were the outbox write moved somewhere shared, this case would go red.
     */
    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `rejecting a proposal writes no outbox row at all`(): Unit = runBlocking {
        val accountId = openAccount()
        grantSavingsDelegation(accountId)

        val rejected = decide(accountId, propose(accountId), approve = false, expectedStatus = "REJECTED")

        assertThat(writersOf(rejected)).isEmpty()
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a proposal
     * id that was never written must produce no pair at all, so `hasSize(1)` is a claim the query
     * is capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a proposal that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val proposalXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(proposalId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT p.xmin::text AS proposal_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM savings_withdrawal_proposals p
            JOIN account_outbox o ON o.aggregate_id = p.id
            WHERE p.id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, proposalId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    private fun openAccount(): String {
        val opened = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {
                  "partyId": "$ownerParty",
                  "productId": "00000000-2222-0000-0000-000000000001",
                  "accountType": "SAVINGS",
                  "currencyCode": "CZK",
                  "legalName": "Outbox Atomicity IT"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        }
        return opened.extract().path("id")
    }

    /**
     * The proposal endpoint is reachable only for a delegate holding a SAVINGS_PROPOSE_WITHDRAW
     * grant, which this service learns from `delegation-events-in`. Same arrangement as
     * [SavingsProposalIT]; the poll below is on the service's own check endpoint, so it observes
     * the projection rather than assuming a delay is enough.
     */
    private suspend fun grantSavingsDelegation(accountId: String) {
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        source.runOnVertxContext(true)
        source.send(
            """
            {
              "eventType": "DelegationActivated",
              "lifecycleRevision": 1,
              "aggregateId": "${UUID.randomUUID()}",
              "grantorPartyId": "$ownerParty",
              "granteePartyId": "$delegateParty",
              "resourceType": "SAVINGS_GOAL",
              "resourceId": "$accountId",
              "capabilities": ["SAVINGS_PROPOSE_WITHDRAW"],
              "validFrom": "2026-01-01T00:00:00Z",
              "occurredAt": "2026-08-01T12:00:00Z"
            }
            """.trimIndent(),
        )
        repeat(GRANT_POLL_ATTEMPTS) {
            val checked = Given { this } When {
                get(
                    "/api/v1/accounts/$accountId/savings-goal/delegation/check" +
                        "?partyId=$delegateParty&intent=PROPOSE_WITHDRAW",
                )
            } Then {
                statusCode(200)
            }
            if (checked.extract().path<Boolean>("authorized")) return
            delay(GRANT_POLL_INTERVAL_MS)
        }
        error("savings grant never became visible")
    }

    private fun propose(accountId: String): String {
        val proposed = Given {
            contentType("application/json")
            header("X-Customer-Party-Id", delegateParty.toString())
            body("""{"amountMinor": 150000, "currency": "CZK", "note": "kolo"}""")
        } When {
            post("/api/v1/accounts/$accountId/savings-goal/delegation/proposals")
        } Then {
            statusCode(202)
        }
        return proposed.extract().path("proposal.id")
    }

    private fun approve(accountId: String, proposalId: String): UUID =
        decide(accountId, proposalId, approve = true, expectedStatus = "APPROVED")

    private fun decide(accountId: String, proposalId: String, approve: Boolean, expectedStatus: String): UUID {
        StubScaChallengeClient.party.set(ownerParty)
        val decided = Given {
            contentType("application/json")
            header("X-Customer-Party-Id", ownerParty.toString())
            body("""{"approve": $approve, "scaSessionId": "${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/accounts/$accountId/savings-goal/delegation/proposals/$proposalId/decide")
        } Then {
            statusCode(200)
        }
        // Arrangement assertion: only the APPROVE branch reaches `save(proposal, event)`. A change
        // that routed this request elsewhere would otherwise leave the test silently asserting over
        // an empty outbox.
        assertThat(decided.extract().path<String>("status")).isEqualTo(expectedStatus)
        return UUID.fromString(proposalId)
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
        const val GRANT_POLL_ATTEMPTS = 40
        const val GRANT_POLL_INTERVAL_MS = 250L

        /** `SavingsWithdrawalApproved.eventType` — the wire value is the subject. */
        const val EVENT_WITHDRAWAL_APPROVED = "SavingsWithdrawalApproved"
    }
}
