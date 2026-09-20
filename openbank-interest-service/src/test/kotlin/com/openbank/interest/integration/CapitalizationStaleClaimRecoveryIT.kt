// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The wedge: a claim taken and never completed freezes its `(account, product)` pair **forever**,
 * and nothing but a WARN line says so.
 *
 * `InterestService.capitalize`'s KDoc states that a claimed set "is always completable" and that
 * "nothing needs an operator". That is true of the METHOD and was false of the SYSTEM, because the
 * only automatic caller — `capitalizeAll`, from `InterestCapitalizationScheduler` — always passes
 * **today**. A claim frozen for an earlier period therefore took the `inFlightClaimFailure` branch
 * on every subsequent tick, forever. Sandbox had 124 accruals across 7 pairs stuck that way since
 * 2026-08-01; every tick refused all 7 and logged `capitalized 0 pair(s)`, which reads exactly like
 * "there was no work to do" (#10404).
 *
 * Why this is an IT and not a unit test: the wedge is a property of *committed* state. The claim is
 * flipped `ACCRUING -> CAPITALIZING` in its own transaction and survives the failed post — a mocked
 * repository cannot express "committed, then the next call sees it", which is the entire mechanism.
 *
 * [LedgerBoundary.failNextPost] reproduces the original cause exactly: interest-service had no
 * `LEDGER_SERVICE_URL`, so `postCreditLeg` dialled `http://localhost:8101` and got connection
 * refused — after the claim had committed.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.interest.it.PostgresRedisTestResource::class)
class CapitalizationStaleClaimRecoveryIT {

    @Inject
    lateinit var service: CapitalizeInterestUseCase

    @Inject
    lateinit var configRepo: InterestRateConfigRepository

    @Inject
    lateinit var accrualRepo: InterestAccrualRepository

    @Inject
    lateinit var ledger: LedgerBoundary

    @Inject
    lateinit var sf: Mutiny.SessionFactory

    @Inject
    lateinit var registry: MeterRegistry

    private val claimedPeriod: LocalDate = LocalDate.of(2026, 8, 1)
    private val laterTick: LocalDate = LocalDate.of(2026, 9, 20)

    @BeforeEach
    fun clearLedger() {
        ledger.reset()
    }

    @Test
    fun `a claim stranded by a failed post is completed by the next sweep, at its own period`() {
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 7, 31))

        // 1. The wedge is created exactly as it was in sandbox: the claim commits, the post fails.
        //    Created through the PER-PAIR method on purpose. capitalizeAll now runs the recovery
        //    sweep first, and LedgerBoundary is application-scoped, so a `failNextPost` armed before
        //    capitalizeAll can be consumed by another test's outstanding claim — leaving this
        //    account's own post to succeed and the wedge never to form. Driving the one pair
        //    directly makes the arming deterministic and states the scenario more plainly anyway.
        ledger.failNextPost("connection refused: localhost:8101")
        assertThatThrownBy { capitalizeOne(accountId, claimedPeriod) }
            .`as`("the ledger post fails after the claim has committed")
            .hasMessageContaining("connection refused")

        assertThat(journalsFor(accountId))
            .`as`("the post failed, so the GL holds nothing FOR THIS ACCOUNT")
            .isEmpty()
        assertThat(accrualsOf(accountId).map { it.status })
            .`as`("the claim COMMITTED and outlived the failed post — this is the wedge")
            .containsOnly(AccrualStatus.CAPITALIZING)
        assertThat(accrualsOf(accountId).map { it.claimedPeriodTo })
            .`as`("frozen for the period the interrupted attempt chose")
            .containsOnly(claimedPeriod)

        // 2. The next scheduled tick runs with TODAY, which is not the claimed period. Before the
        //    fix this hit inFlightClaimFailure and returned 0 — forever.
        val recoveredBefore = counter("openbank.interest.capitalization.claims.recovered")
        val recovered = capitalizeAll(laterTick)
        assertThat(recovered).`as`("the stale claim was completed, not refused").isGreaterThanOrEqualTo(1)

        // 3. Completed at the CLAIMED period, so the idempotency key is the one the first attempt
        //    would have used — not a new one derived from today.
        val expectedKey = "interest-capitalization-$accountId-$PRODUCT-$claimedPeriod"
        assertThat(ledger.journalFor(expectedKey))
            .`as`("recovery replays the frozen period's key")
            .isNotNull
        assertThat(ledger.journalFor("interest-capitalization-$accountId-$PRODUCT-$laterTick"))
            .`as`("and must NEVER mint a key for today — that would be a second journal")
            .isNull()

        assertThat(accrualsOf(accountId).map { it.status })
            .`as`("the set is finished, so the pair is no longer wedged")
            .containsOnly(AccrualStatus.CAPITALIZED)

        // The metric, not just the log. This defect hid for ~7 weeks behind a WARN, so shipping the
        // self-heal without a counter would rebuild the same trap one level up: a silent recovery is
        // as unobservable as a silent wedge.
        assertThat(counter("openbank.interest.capitalization.claims.recovered"))
            .`as`("recovering a stale claim increments the counter an alert would read")
            .isGreaterThanOrEqualTo(recoveredBefore + 1.0)
        // The gauge is registered and readable — `gauge()` returns the -1.0 sentinel when the meter
        // does not exist, so this fails if the registration is ever dropped. Deliberately NOT pinned
        // to 0.0: the meter is process-wide and other tests in the same Quarkus instance create and
        // recover their own claims, so an exact global value would be asserting their behaviour, not
        // this test's. What matters per-account is asserted above (status CAPITALIZED, one journal).
        assertThat(gauge("openbank.interest.capitalization.claims.outstanding"))
            .`as`("the outstanding gauge is registered, so an alert has something to read and clear")
            .isGreaterThanOrEqualTo(0.0)
    }

    /**
     * The idempotency proof, stated as the thing that would actually hurt: recovery must not post a
     * SECOND journal for the same `(account, product, period)`.
     *
     * Releasing a stale claim back to `ACCRUING` and re-claiming it under a later period is the
     * tempting "reclaim" fix and is precisely this defect — the key is derived from the period, so a
     * later period books a second credit for accruals the first journal may already have paid.
     */
    @Test
    fun `repeated sweeps over a recovered claim leave exactly one journal`() {
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 7, 31))

        ledger.failNextPost("connection refused: localhost:8101")
        assertThatThrownBy { capitalizeOne(accountId, claimedPeriod) }
            .hasMessageContaining("connection refused")
        assertThat(journalsFor(accountId)).isEmpty()

        // Three further sweeps, each at a DIFFERENT "today" — the shape a 5-minute cron produces.
        capitalizeAll(laterTick)
        capitalizeAll(laterTick.plusDays(1))
        capitalizeAll(laterTick.plusDays(2))

        assertThat(journalsFor(accountId))
            .`as`("one claim, one period, one journal — no matter how many sweeps run")
            .hasSize(1)
        assertThat(journalsFor(accountId).single().idempotencyKey)
            .isEqualTo("interest-capitalization-$accountId-$PRODUCT-$claimedPeriod")
    }

    /**
     * Journals for THIS account only.
     *
     * [LedgerBoundary] is `@ApplicationScoped`, so it is shared by every test in the Quarkus
     * instance — and the recovery sweep this class tests legitimately completes claims left
     * outstanding by OTHER tests (notably the crash-after-booking case in
     * [CapitalizationLedgerBoundaryIT]). A whole-ledger `isEmpty()` therefore fails in a full-suite
     * run while passing in isolation, which says nothing about the code under test. Scoping to the
     * account is also the assertion actually meant: this test is about one pair's claim.
     */
    private fun journalsFor(accountId: UUID) =
        ledger.booked().filter { it.idempotencyKey.contains(accountId.toString()) }

    private fun counter(name: String): Double = registry.find(name).counter()?.count() ?: 0.0

    private fun gauge(name: String): Double = registry.find(name).gauge()?.value() ?: -1.0

    private fun capitalizeOne(accountId: UUID, toDate: LocalDate) =
        VertxContextSupport.subscribeAndAwait { service.capitalize(accountId, PRODUCT, toDate) }

    private fun capitalizeAll(toDate: LocalDate): Int =
        VertxContextSupport.subscribeAndAwait { service.capitalizeAll(toDate) }!!

    private fun accrualsOf(accountId: UUID): List<InterestAccrualEntity> = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s ->
            s.createQuery(
                "FROM InterestAccrualEntity a WHERE a.accountId = :a",
                InterestAccrualEntity::class.java,
            ).setParameter("a", accountId).resultList
        }
    }!!

    private fun persistConfig(productId: String, annualRate: BigDecimal): UUID = VertxContextSupport.subscribeAndAwait {
        configRepo.save(
            InterestRateConfig(
                productId = productId,
                currency = "CZK",
                annualRate = annualRate,
                effectiveFrom = LocalDate.of(2026, 1, 1),
                createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }!!.id

    private fun persistAccrual(accountId: UUID, accrued: String, date: LocalDate) {
        val config = persistConfig(PRODUCT, BigDecimal("0.365000"))
        val entity = InterestAccrualEntity().apply {
            id = UUID.randomUUID()
            this.accountId = accountId
            productId = PRODUCT
            configId = config
            accrualDate = date
            balance = BigDecimal("1000.00")
            dailyRate = BigDecimal("0.0010000000")
            accruedAmount = BigDecimal(accrued)
            currency = "CZK"
            status = AccrualStatus.ACCRUING
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z")
        }
        VertxContextSupport.subscribeAndAwait { sf.withTransaction { s -> s.persist(entity) } }
    }

    private companion object {
        private const val PRODUCT = "SAVINGS_CZK_RECOVERY"
    }
}
