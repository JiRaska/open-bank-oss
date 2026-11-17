// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.e2e

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.LedgerPostingPort
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.product.FeeContext
import io.quarkus.test.Mock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The billing money path over the real HTTP surface: dry-run assess → post (idempotent) →
 * observe the ledger confirming the charge → reverse (idempotent) → observe the compensating
 * journal reach the ledger boundary. Ordered because the lifecycle is a state machine — each
 * step is only reachable from the previous one.
 *
 * The integration tests in this module prove one property each (outbox atomicity via xmin,
 * replay persistence, four-eyes DB constraints); this proves the operator-visible route
 * sequence serves the whole charge-then-correct journey over HTTP — that a wiring regression
 * that deregisters a route (#3371), breaks the idempotent replay, or reports the wrong
 * posting status for a lifecycle step fails here even when every table-level assertion
 * stays green.
 *
 * ### The read-back surface (why the journey re-POSTs to observe state)
 *
 * Billing is write-oriented: it exposes no GET for an assessment. The idempotent replay is
 * the durable read-back: `POST /fees/post` for an already-assessed cycle/account/currency
 * re-reads the persisted rows (`BillingCycleService.assessAndPost` → `findExisting`), so the
 * journey polls it to watch the outbox dispatcher flip PENDING → POSTED and later
 * REVERSAL_PENDING → REVERSED — the same "assert on a different code path than the write"
 * discipline as `LedgerPostingJourneyE2E`.
 *
 * ### The three outbound boundaries stand in for their services
 *
 * Exactly like `DelegationLifecycleE2E` (the fleet's established pattern for a money-path
 * journey whose ceremony dependencies live outside the module): the account-context and
 * catalog-revision ports return canned facts, and the ledger posting port records every
 * journal command and answers deterministically. The fake ledger blocks the compensating
 * reversal until the test releases it, so the idempotent-replay assertions observe
 * REVERSAL_PENDING without racing the dispatcher's flip to REVERSED. Everything inside
 * the module — HTTP routes, validation, fee evaluation, persistence, outbox, dispatcher —
 * is the real, CDI-wired code.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(BillingFeeLifecycleE2E.JourneyProfile::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
class BillingFeeLifecycleE2E {

    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // No OPA sidecar answers in a @QuarkusTest; billing fails closed on
            // @Authorize routes when enforcement is on.
            "authz.enforce" to "false",
            // billing's %test disables the @Scheduled ticks (ADR-0050) so ITs drive dispatch
            // explicitly — but this journey's whole point is watching the REAL dispatcher flip
            // the fee through the outbox, so the scheduler must tick here.
            "quarkus.scheduler.enabled" to "true",
            // A profile forces this class its own Quarkus boot, and billing's %test leaves
            // quarkus.http.test-port at the fixed default 8081 — the restart re-binds it
            // before the outgoing instance released it (QuarkusBindException, measured in
            // BillingOutboxAtomicityIT). Port 0 lets the OS assign a free one; Quarkus
            // rewrites the property to the bound port, so RestAssured still finds it.
            "quarkus.http.test-port" to "0",
            // One event loop: the HTTP requests and the dispatcher's ledger call then always
            // share a thread, so any fake that BLOCKS that thread (instead of suspending)
            // hangs the next request deterministically rather than only when the scheduler
            // happens to pick the same loop as RestAssured's connection.
            "quarkus.vertx.event-loops-pool-size" to "1",
        )
    }

    /** Canned account facts for the one account this journey bills; null for anything else. */
    @Mock
    @ApplicationScoped
    class FakeAccountContextPort : AccountContextPort {
        override suspend fun resolve(accountId: String, currency: String): AccountBilling? {
            if (accountId != KNOWN_ACCOUNT) return null
            return AccountBilling(
                productId = PRODUCT_ID,
                context = FeeContext(balance = BigDecimal("5000.00"), currency = currency),
                openedAt = Instant.parse("2026-01-15T10:15:30Z"),
            )
        }
    }

    /** One published revision carrying exactly one non-waivable CZK maintenance fee. */
    @Mock
    @ApplicationScoped
    class FakeProductRevisionPort : ProductRevisionPort {
        override suspend fun publishedRevisionIdFor(productId: String, effectiveAt: Instant?): String = REVISION_ID

        override suspend fun terms(revisionId: String, currency: String): RevisionTerms = RevisionTerms(
            revisionId = revisionId,
            accountingClass = "CUSTOMER_DEPOSIT_DEMAND",
            accountingClassSource = AccountingClassSource.REVISION,
            fees = listOf(
                BillableFee(
                    feeId = FEE_ID,
                    name = "Monthly maintenance",
                    type = "MAINTENANCE",
                    amount = BigDecimal("150.00"),
                    currency = currency,
                    waivable = false,
                    waiveCondition = null,
                ),
            ),
            feeBearing = true,
        )
    }

    /**
     * The ledger boundary: records every command, returns a deterministic journal id, and
     * blocks the compensating reversal on a latch the test releases once the
     * idempotent-replay assertions have observed REVERSAL_PENDING.
     *
     * The hold must SUSPEND, never block: the dispatcher calls this on a Vert.x event loop,
     * and a parked loop thread cannot serve the HTTP requests the test sends while the
     * reversal is held — they time out (`SocketTimeoutException`) whenever they land on
     * the same loop, which is what made this journey flaky on unrelated PRs.
     */
    @Mock
    @ApplicationScoped
    class FakeLedgerPostingPort : LedgerPostingPort {
        val posted = CopyOnWriteArrayList<FeeJournalCommand>()
        val reversals = CopyOnWriteArrayList<FeeReversalCommand>()
        val reversalRecorded = CountDownLatch(1)
        val releaseReversal = CompletableDeferred<Unit>()

        override suspend fun post(command: FeeJournalCommand): UUID {
            posted.add(command)
            return UUID.randomUUID()
        }

        override suspend fun postReversal(command: FeeReversalCommand): UUID {
            reversals.add(command)
            reversalRecorded.countDown()
            withTimeout(TimeUnit.SECONDS.toMillis(60)) { releaseReversal.await() }
            return UUID.randomUUID()
        }
    }

    @Inject
    lateinit var ledger: FakeLedgerPostingPort

    private val cycle = "2026-06"
    private val unknownAccount = "acc-e2e-unknown-${UUID.randomUUID()}"
    private val key = "fee-$cycle-$KNOWN_ACCOUNT-$FEE_ID-CZK"
    private val reversalKey = "fee-reversal-$cycle-$KNOWN_ACCOUNT-$FEE_ID-CZK"

    @Test
    @Order(1)
    fun `1 - dry-run assess prices the fee and fails closed on an unresolvable account`() {
        // Known account: one chargeable fee at the pinned revision's terms.
        given()
            .post("/api/v1/fees/assess?cycleId=$cycle&accountId=$KNOWN_ACCOUNT&currency=CZK")
            .then()
            .statusCode(200)
            .body("skipped", equalTo(false))
            .body("assessedFees", notNullValue())
            .body("assessedFees[0].feeId", equalTo(FEE_ID))
            .body("assessedFees[0].chargedAmount", equalTo(150.00f))
            .body("assessedFees[0].waived", equalTo(false))
            .body("assessedFees[0].postingStatus", equalTo("NOT_APPLICABLE"))

        // Unknown account: billing refuses to charge on absent inputs (ADR-0143).
        given()
            .post("/api/v1/fees/assess?cycleId=$cycle&accountId=$unknownAccount&currency=CZK")
            .then()
            .statusCode(200)
            .body("skipped", equalTo(true))
            .body("skipReason", equalTo("ACCOUNT_CONTEXT_UNRESOLVED"))
            .body("assessedFees.size()", equalTo(0))
    }

    @Test
    @Order(2)
    fun `2 - post persists the charge intent and the fee starts PENDING`() {
        given()
            .post("/api/v1/fees/post?cycleId=$cycle&accountId=$KNOWN_ACCOUNT&currency=CZK")
            .then()
            .statusCode(200)
            .body("skipped", equalTo(false))
            .body("assessedFees.size()", equalTo(1))
            .body("assessedFees[0].feeId", equalTo(FEE_ID))
            .body("assessedFees[0].chargedAmount", equalTo(150.00f))
            .body("assessedFees[0].postingStatus", equalTo("PENDING"))
            .body("assessedFees[0].journalId", nullValue())
            .body("assessedFees[0].idempotencyKey", equalTo(key))
            .body("assessedFees[0].productRevisionId", equalTo(REVISION_ID))
    }

    @Test
    @Order(3)
    fun `3 - the idempotent replay is the read-back surface and shows the ledger confirming POSTED`() {
        // Re-posting the same cycle/account/currency replays the persisted assessment —
        // never a second charge. The dispatcher's ledger call (real outbox → fake boundary)
        // flips PENDING → POSTED; poll the replay until that durable state is visible.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var status: String? = null
        while (System.nanoTime() < deadline) {
            status = postFees().then()
                .statusCode(200)
                .body("assessedFees.size()", equalTo(1))
                .extract()
                .path<String>("assessedFees[0].postingStatus")
            if (status == "POSTED") break
            Thread.sleep(500)
        }
        org.assertj.core.api.Assertions.assertThat(status)
            .describedAs("the outbox dispatcher must confirm the charge with the ledger within 30s (replay surface)")
            .isEqualTo("POSTED")

        postFees().then()
            .statusCode(200)
            .body("assessedFees.size()", equalTo(1))
            .body("assessedFees[0].postingStatus", equalTo("POSTED"))
            .body("assessedFees[0].journalId", notNullValue())
            .body("assessedFees[0].idempotencyKey", equalTo(key))

        // The charge reached the ledger boundary exactly once, under the charge key.
        org.assertj.core.api.Assertions.assertThat(ledger.posted.map { it.idempotencyKey })
            .describedAs("exactly one charge journal, idempotent on the fee key")
            .containsExactly(key)
    }

    @Test
    @Order(4)
    fun `4 - reverse parks the fee at REVERSAL_PENDING and a replay refuses a second reversal`() {
        given()
            .contentType("application/json")
            .body("""{"reason":"assessed under a superseded tariff"}""")
            .post("/api/v1/fees/reverse?idempotencyKey=$key")
            .then()
            .statusCode(200)
            .body("postingStatus", equalTo("REVERSAL_PENDING"))
            .body("reversalReason", equalTo("assessed under a superseded tariff"))
            .body("journalId", notNullValue())

        // The compensating journal reached the boundary before the test releases it; the
        // fake ledger is still holding the flip to REVERSED, so the replayed state is
        // deterministically REVERSAL_PENDING — and re-reversing is an idempotent replay,
        // not a second compensating journal.
        org.assertj.core.api.Assertions.assertThat(ledger.reversalRecorded.await(30, TimeUnit.SECONDS))
            .describedAs("the reversal intent must reach the ledger boundary")
            .isTrue()

        given()
            .contentType("application/json")
            .body("""{"reason":"duplicate reversal attempt"}""")
            .post("/api/v1/fees/reverse?idempotencyKey=$key")
            .then()
            .statusCode(200)
            .body("postingStatus", equalTo("REVERSAL_PENDING"))
            .body("reversalReason", equalTo("assessed under a superseded tariff"))

        // Exactly one reversal command, under the reversal key — distinct from the charge
        // key, so the ledger's own idempotency store can never collapse a reversal into a
        // replay of the original charge (ADR-0143 phase 2e).
        org.assertj.core.api.Assertions.assertThat(ledger.reversals.map { it.idempotencyKey })
            .describedAs("exactly one compensating journal, idempotent on the reversal key")
            .containsExactly(reversalKey)
        org.assertj.core.api.Assertions.assertThat(ledger.reversals[0].originalIdempotencyKey)
            .isEqualTo(key)

        // Release the held ledger call; the dispatcher completes the flip.
        ledger.releaseReversal.complete(Unit)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var status: String? = null
        while (System.nanoTime() < deadline) {
            status = postFees().then()
                .statusCode(200)
                .extract()
                .path<String>("assessedFees[0].postingStatus")
            if (status == "REVERSED") break
            Thread.sleep(500)
        }
        org.assertj.core.api.Assertions.assertThat(status)
            .describedAs("the compensating journal must complete the flip to REVERSED")
            .isEqualTo("REVERSED")
    }

    @Test
    @Order(5)
    fun `5 - the journey fails closed on unknown fees, missing reasons and unknown currencies`() {
        // Unknown idempotency key: nothing to reverse.
        given()
            .contentType("application/json")
            .body("""{"reason":"no such fee"}""")
            .post("/api/v1/fees/reverse?idempotencyKey=fee-never-assessed")
            .then()
            .statusCode(404)

        // Missing reason: the endpoint validates before touching the store.
        given()
            .contentType("application/json")
            .body("""{}""")
            .post("/api/v1/fees/reverse?idempotencyKey=$key")
            .then()
            .statusCode(400)

        // Unknown ISO currency: parameter validation rejects it before any port is called.
        given()
            .post("/api/v1/fees/post?cycleId=$cycle&accountId=$KNOWN_ACCOUNT&currency=XXX")
            .then()
            .statusCode(400)
    }

    private fun postFees(): Response = given()
        .post("/api/v1/fees/post?cycleId=$cycle&accountId=$KNOWN_ACCOUNT&currency=CZK")

    private companion object {
        const val KNOWN_ACCOUNT = "acc-e2e-billing"
        const val PRODUCT_ID = "CURRENT_CZK"
        const val FEE_ID = "maintenance-monthly"
        const val REVISION_ID = "0191c0de-0000-7000-8000-0000000000a1"
    }
}
