// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.audit.application.AuditConsumer
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * A delegated payment is auditable AS DELEGATED, and that record is under tamper-evidence
 * (ADR-0232 D5 / ADR-0086, #2990 AC9/AC10).
 *
 * The bar this file is written to: **asserting that a field is present proves nothing about a
 * tamper-evident log.** `onBehalfOf` could be a column somebody can UPDATE, and every
 * field-presence assertion in the suite would still pass. So the tests below verify the CHAIN —
 * they recompute every SHA-256 link over the row as the database returns it — and then prove the
 * chain actually reacts by editing the stored evidence and watching `verifyChain` go BROKEN at
 * exactly the row that was edited.
 *
 * The event JSON is the real wire shape: `EdgeAuditPublisher.emit` flattens its `details` map into
 * top-level fields, so a delegated payment arrives with `onBehalfOf`/`delegationId` beside
 * `partyId`/`operation`, and the whole document is what `payload` stores and the chain hashes.
 * Building it by hand here rather than importing an edge fixture is deliberate: this file is the
 * consumer half of a cross-service contract, and a shared builder would let both halves drift
 * together. If customer-edge changes the field names, this test must go red.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DelegatedActionAuditChainIT {

    @Inject
    lateinit var repository: AuditRepository

    private val consumer = AuditConsumer()

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun consumerFor(): AuditConsumer = consumer.also {
        it.repo = repository
        it.objectMapper = jacksonObjectMapper().findAndRegisterModules()
        it.clock = java.time.Clock.systemUTC()
    }

    /** Exactly what `EdgeAuditPublisher.emit` puts on `openbank.customer.audit` for a delegated pay. */
    private fun delegatedPaymentEvent(delegate: UUID, grantor: UUID, delegationId: UUID, paymentId: UUID) = """
        {
          "eventType": "CUSTOMER_PAYMENT_INITIATED",
          "aggregateType": "CUSTOMER_ACTION",
          "partyId": "$delegate",
          "actorId": "$delegate",
          "actorType": "CUSTOMER",
          "operation": "payments.domestic",
          "result": "SUCCESS",
          "resourceId": "$paymentId",
          "sourceService": "customer-edge",
          "occurredAt": "2026-08-02T09:15:00.123456Z",
          "resourceType": "PAYMENT",
          "amount": "1500.00",
          "currency": "CZK",
          "onBehalfOf": "$grantor",
          "delegationId": "$delegationId"
        }
    """.trimIndent()

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    /**
     * The headline: the delegated payment lands in the chain, names the delegate as the ACTOR and
     * the grantor as the on-behalf-of party, and the chain VERIFIES from that row.
     */
    @Test
    fun `a delegated payment is chained, and the chain verifies from that entry`() {
        val delegate = UUID.randomUUID()
        val grantor = UUID.randomUUID()
        val delegationId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()

        onEventLoop {
            consumerFor().consume(delegatedPaymentEvent(delegate, grantor, delegationId, paymentId))
        }

        val entry = onEventLoop { repository.findByActorId(delegate.toString()) }.single()
        // Who acted does not change because they were allowed to: the actor is the DELEGATE.
        assertThat(entry.actorId).isEqualTo(delegate.toString())
        assertThat(entry.onBehalfOf).isEqualTo(grantor.toString())
        assertThat(entry.delegationId).isEqualTo(delegationId.toString())

        val verification = onEventLoop { repository.verifyChain(fromEntryId = entry.id) }
        assertThat(verification.intact)
            .describedAs("chain must verify from the delegated entry (broken at: %s)", verification.firstBrokenEntryId)
            .isTrue()
        assertThat(verification.checked)
            .describedAs("the delegated row must actually be recomputed, not skipped as unchained")
            .isGreaterThanOrEqualTo(1)
        assertThat(verification.unchained)
            .describedAs("a freshly written row is never pre-chain")
            .isZero()
    }

    /**
     * The falsification, and the demonstration of BOTH controls that stand behind a delegated
     * payment's audit record.
     *
     * 1. `audit_entries` carries the V2 `no_update_audit` RULE (`DO INSTEAD NOTHING`), so an
     *    ordinary UPDATE is silently discarded — it does not error, it affects **zero rows**. That
     *    is asserted here rather than assumed: a rule that had been dropped would look identical
     *    from the application, and this is the only assertion in the suite that would notice.
     * 2. The hash chain is what catches an edit that BYPASSES the rule — precisely V5's stated
     *    claim ("the chain makes any mutation that bypasses them detectable"), which nothing had
     *    ever exercised. So the test drops the rule, performs the edit a falsified
     *    account-sharing story would need, and requires `verifyChain` to break at exactly that row.
     *
     * Without this the test above is unfalsified: it has only ever seen an intact chain, and
     * "intact" is also what a chain that checks nothing returns.
     */
    @Test
    fun `rewriting the on-behalf-of party inside the payload BREAKS the chain at that entry`() {
        val delegate = UUID.randomUUID()
        val grantor = UUID.randomUUID()
        val attackersGrantor = UUID.randomUUID()

        onEventLoop {
            consumerFor().consume(
                delegatedPaymentEvent(delegate, grantor, UUID.randomUUID(), UUID.randomUUID()),
            )
        }
        val entry = onEventLoop { repository.findByActorId(delegate.toString()) }.single()

        // Sanity: the chain is intact BEFORE the edit, so a later BROKEN is caused by the edit and
        // not by an unrelated row. (A known-negative before the known-positive.)
        assertThat(onEventLoop { repository.verifyChain(fromEntryId = entry.id) }.intact).isTrue()

        val original = entry.payload
        val tampered = original.replace(grantor.toString(), attackersGrantor.toString())
        assertThat(tampered).describedAs("the tamper must change something").isNotEqualTo(original)

        // Control 1 — the append-only RULE. Note it reports SUCCESS with zero rows affected, so an
        // attacker learns nothing from the return code and the log is simply unchanged.
        assertThat(updatePayload(entry.id, tampered))
            .describedAs("the no_update_audit RULE must silently discard an in-place edit")
            .isZero()
        assertThat(onEventLoop { repository.verifyChain(fromEntryId = entry.id) }.intact)
            .describedAs("nothing changed, so the chain is still intact")
            .isTrue()

        // Control 2 — the chain, for an edit that gets past the rule (a DBA, a restored dump, a
        // migration that drops it). This is V5's claim, exercised.
        try {
            withoutImmutabilityRule {
                assertThat(updatePayload(entry.id, tampered))
                    .describedAs("with the rule dropped, the edit must actually land")
                    .isEqualTo(1)
            }
            val after = onEventLoop { repository.verifyChain(fromEntryId = entry.id) }
            assertThat(after.intact)
                .describedAs("an edited on-behalf-of party must not verify")
                .isFalse()
            assertThat(after.firstBrokenEntryId)
                .describedAs("the chain must name the row that was edited")
                .isEqualTo(entry.id)
        } finally {
            // The chain is shared with every other test in this JVM: leaving a broken link behind
            // would make an unrelated test fail for a reason it cannot explain.
            withoutImmutabilityRule { updatePayload(entry.id, original) }
        }
        assertThat(onEventLoop { repository.verifyChain(fromEntryId = entry.id) }.intact)
            .describedAs("restoring the original bytes restores the chain — the BREAK was the edit")
            .isTrue()
    }

    /** Rows affected. Zero while the V2 `no_update_audit` RULE is in place — by design. */
    private fun updatePayload(entryId: UUID, payload: String): Int = jdbc().use { c ->
        c.prepareStatement("UPDATE audit_entries SET payload = ? WHERE entry_id = ?::uuid").use { ps ->
            ps.setString(1, payload)
            ps.setString(2, entryId.toString())
            ps.executeUpdate()
        }
    }

    /** Temporarily lifts the append-only RULE so the chain can be tested against a real mutation. */
    private fun withoutImmutabilityRule(block: () -> Unit) = jdbc().use { c ->
        c.createStatement().use { it.execute("DROP RULE no_update_audit ON audit_entries") }
        try {
            block()
        } finally {
            c.createStatement().use {
                it.execute("CREATE OR REPLACE RULE no_update_audit AS ON UPDATE TO audit_entries DO INSTEAD NOTHING")
            }
        }
    }

    /**
     * The transparency query (AC10): the grantor sees what their delegate did, and only that.
     *
     * The negative half is the point. Before `onBehalfOf` existed, a delegated payment was
     * attributed entirely to the delegate — actor AND aggregate — so the grantor's own access log
     * showed nothing whatsoever about money leaving their account. This asserts the query is
     * scoped, not merely non-empty.
     */
    @Test
    fun `the grantor query returns their delegate's actions and nobody else's`() {
        val grantor = UUID.randomUUID()
        val otherGrantor = UUID.randomUUID()
        val delegateA = UUID.randomUUID()
        val delegateB = UUID.randomUUID()
        val grantA = UUID.randomUUID()

        onEventLoop {
            val c = consumerFor()
            c.consume(delegatedPaymentEvent(delegateA, grantor, grantA, UUID.randomUUID()))
            c.consume(delegatedPaymentEvent(delegateB, grantor, UUID.randomUUID(), UUID.randomUUID()))
            // Somebody else's delegated payment, and a direct (non-delegated) one.
            c.consume(delegatedPaymentEvent(delegateA, otherGrantor, UUID.randomUUID(), UUID.randomUUID()))
            c.consume(
                """
                {"eventType":"CUSTOMER_PAYMENT_INITIATED","aggregateType":"CUSTOMER_ACTION",
                 "partyId":"$grantor","actorId":"$grantor","operation":"payments.domestic",
                 "sourceService":"customer-edge","occurredAt":"2026-08-02T09:20:00Z"}
                """.trimIndent(),
            )
        }

        val mine = onEventLoop { repository.findOnBehalfOf(grantor.toString()) }
        assertThat(mine).hasSize(2)
        assertThat(mine.map { it.actorId }).containsExactlyInAnyOrder(delegateA.toString(), delegateB.toString())
        assertThat(mine.map { it.onBehalfOf }.distinct()).containsExactly(grantor.toString())

        // Narrowed to one delegate…
        assertThat(onEventLoop { repository.findOnBehalfOf(grantor.toString(), delegateA.toString()) })
            .singleElement().extracting { it.actorId }.isEqualTo(delegateA.toString())
        // …and to one grant.
        assertThat(onEventLoop { repository.findOnBehalfOf(grantor.toString(), delegationId = grantA.toString()) })
            .singleElement().extracting { it.delegationId }.isEqualTo(grantA.toString())

        // The grantor's OWN direct payment is not an on-behalf-of action and must not appear here.
        assertThat(mine.map { it.actorId }).doesNotContain(grantor.toString())
    }

    /** A direct action must leave both columns null — an index that fires on everything is no index. */
    @Test
    fun `a non-delegated action carries no on-behalf-of party`() {
        val party = UUID.randomUUID()
        onEventLoop {
            consumerFor().consume(
                """
                {"eventType":"CUSTOMER_PAYMENT_INITIATED","aggregateType":"CUSTOMER_ACTION",
                 "partyId":"$party","actorId":"$party","operation":"payments.domestic",
                 "sourceService":"customer-edge","occurredAt":"2026-08-02T09:25:00Z"}
                """.trimIndent(),
            )
        }
        val entry = onEventLoop { repository.findByActorId(party.toString()) }.single()
        assertThat(entry.onBehalfOf).isNull()
        assertThat(entry.delegationId).isNull()
        assertThat(onEventLoop { repository.findOnBehalfOf(party.toString()) }).isEmpty()
    }
}
