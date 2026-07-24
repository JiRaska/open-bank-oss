// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestCapitalizationEntity
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
 * Drives the REAL [CapitalizeInterestUseCase] — real Postgres, real repositories, real
 * `CapitalizationJournalFactory` — across the ledger boundary ([LedgerBoundary], which constructs
 * every line through the production [com.openbank.libs.domain.money.Money] exactly as
 * `LedgerService.postJournal` does, and replays an idempotency key exactly as it does).
 *
 * This test exists because the PR's original suite could not see a defect that broke **every**
 * money-bearing capitalization: `CapitalizationJournalFactoryTest` stopped at the DTO,
 * `InterestServiceTest` mocked `LedgerPostingPort`, and `CapitalizationTransactionIT` injected the
 * repository rather than the use case. Nothing joined the two ends, so nothing asked the one
 * question that matters on a money path: **does the amount the GL booked equal the amount the
 * capitalization row claims?**
 *
 * See [LedgerBoundary]'s KDoc for exactly what is real here and what is not.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.interest.it.PostgresRedisTestResource::class)
class CapitalizationLedgerBoundaryIT {

    @Inject
    lateinit var service: CapitalizeInterestUseCase

    @Inject
    lateinit var accrualService: AccrueInterestUseCase

    @Inject
    lateinit var configRepo: InterestRateConfigRepository

    @Inject
    lateinit var accrualRepo: InterestAccrualRepository

    @Inject
    lateinit var ledger: LedgerBoundary

    @Inject
    lateinit var sf: Mutiny.SessionFactory

    private val periodTo: LocalDate = LocalDate.of(2026, 1, 20)

    @BeforeEach
    fun clearLedger() {
        ledger.reset()
    }

    // --- Finding 1: every posting 400'd, in every currency, under every treatment ---------------

    @Test
    fun `capitalize posts a journal the ledger can actually book, and its legs match the row`() {
        val accountId = UUID.randomUUID()
        // 60.002500 + 40.002499 = 100.004999 — a scale-6 accrual sum, the normal case. The old code
        // rounded it to 100.0050 (scale 4) and handed that to the port as a bare BigDecimal;
        // Money.of("100.0050", CZK) throws, so the ledger 400'd and NO capitalization ever
        // completed. Rounding now happens once, at the source, to the currency's minor units.
        persistAccrual(accountId, "60.002500", LocalDate.of(2026, 1, 18))
        persistAccrual(accountId, "40.002499", LocalDate.of(2026, 1, 19))

        val cap = capitalize(accountId)

        val journal = ledger.journalFor("interest-capitalization-$accountId-$PRODUCT-$periodTo")
        assertThat(journal).`as`("the credit reached the GL at all").isNotNull
        assertThat(ledger.booked()).hasSize(1)

        // THE assertion this whole test exists for: the GL and the capitalization row agree, to the
        // digit. Not isEqualByComparingTo — scale is the property that broke, and comparing by value
        // ignores it by construction.
        val debit = journal!!.debits().single()
        assertThat(debit.glAccountId).isEqualTo(TestInterestLedgerConfig.interestExpenseCzk)
        assertThat(debit.amount.amount).isEqualTo(cap.grossAmount)
        assertThat(debit.amount.amount.scale()).isEqualTo(2)

        val deposit = journal.credits().single { it.glAccountId == TestInterestLedgerConfig.depositControlCzk }
        assertThat(deposit.amount.amount).isEqualTo(cap.netAmount)
        assertThat(deposit.amount.amount.scale()).isEqualTo(2)
        // The sub-ledger dimension, or the next tie-out run breaks instead.
        assertThat(deposit.subAccountId).isEqualTo(accountId)

        val tax = journal.credits().single { it.glAccountId == TestInterestLedgerConfig.withholdingTaxPayableCzk }
        assertThat(tax.amount.amount).isEqualTo(cap.taxAmount)

        // gross 100.00 -> base 100, tax 15, net 85.00 (WithholdingTaxPolicy, whole-CZK DOWN).
        assertThat(cap.grossAmount).isEqualTo(BigDecimal("100.00"))
        assertThat(cap.taxAmount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(cap.netAmount).isEqualTo(BigDecimal("85.00"))
        // The raw accrual sum survives at full precision: an accrual is a measurement, not money,
        // and V6's partial unique index keys on `total_accrued <> 0`.
        assertThat(cap.totalAccrued).isEqualByComparingTo(BigDecimal("100.004999"))

        // ...and the row that was actually committed to Postgres says the same thing.
        //
        // isEqualByComparingTo is the right comparator for the VALUE here (unlike the scale-4-vs-2
        // bug this suite exists to catch, there is no rounding step between "cap" and "persisted" —
        // both are the same in-memory BigDecimal before and after the round-trip, so a numeric
        // comparison genuinely proves the row is faithful). It is NOT the whole story: this table's
        // gross_amount/net_amount columns are NUMERIC(20,4) (V3__withholding_tax.sql), so Postgres
        // always returns scale 4 regardless of what was written — "100.00" round-trips as "100.0000".
        // That is a representation gap from what was actually posted to the ledger at currency scale,
        // even though the value is numerically identical. Asserting the scale explicitly here, rather
        // than silently normalizing it away, is what makes that gap visible instead of hidden — see
        // the linked issue for narrowing the column to currency scale.
        val persisted = persistedCap(cap.id)
        assertThat(persisted.grossAmount).isEqualByComparingTo(cap.grossAmount)
        assertThat(persisted.netAmount).isEqualByComparingTo(cap.netAmount)
        val scaleGapNote =
            "interest_capitalizations.gross_amount is NUMERIC(20,4); this is scale 4 even though " +
                "the ledger was posted at currency scale (2) -- a known representation gap, not a value bug"
        assertThat(persisted.grossAmount.scale()).`as`(scaleGapNote).isEqualTo(4)
    }

    // --- Finding 2: the idempotency key is amount-blind -----------------------------------------

    @Test
    fun `an accrual backfilled after a crashed post cannot make the row and the GL disagree`() {
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        // Attempt 1: the ledger books J(key, 100) and the pod dies before saveWithOutbox commits.
        ledger.crashAfterNextBooking("simulated crash after the ledger booked, before the local commit")
        assertThatThrownBy { capitalize(accountId) }.hasMessageContaining("simulated crash")

        val key = "interest-capitalization-$accountId-$PRODUCT-$periodTo"
        assertThat(ledger.journalFor(key)!!.debits().single().amount.amount).isEqualTo(BigDecimal("100.00"))
        assertThat(capRowCount(accountId)).`as`("nothing committed locally").isZero()
        // The claim survived the crash — that is what pins the retry to the same 100.
        assertThat(statusesOf(accountId)).containsOnly(AccrualStatus.CAPITALIZING)

        // A missed day is backfilled for an EARLIER date. findPendingCapitalization has no lower
        // bound, so before the claim this accrual silently joined the retry's set and made it 120.
        persistAccrual(accountId, "20.000000", LocalDate.of(2026, 1, 17))

        // Attempt 2: a plain retry, same arguments. No operator, no manual status surgery.
        val cap = capitalize(accountId)

        // The divergence that used to happen here: interest-service committed 120 / tax 18 / net 102
        // while the GL and the customer had moved 100 — the ledger returns the first journal from
        // findByIdempotencyKey without ever looking at the amount. The remittance then paid 18 CZK
        // of real cash on 20 CZK the customer never got. Nothing reconciles the two sides, so it was
        // permanent and silent.
        assertThat(cap.grossAmount).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(cap.taxAmount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(cap.netAmount).isEqualByComparingTo(BigDecimal("85.00"))
        assertThat(ledger.journalFor(key)!!.debits().single().amount.amount).isEqualByComparingTo(cap.grossAmount)

        // And exactly ONE journal — putting the amount in the key would have posted a second one and
        // turned a silent understatement into a double credit (85 + 102 for 120 owed).
        assertThat(ledger.booked()).`as`("no double credit").hasSize(1)
        assertThat(capRowCount(accountId)).isEqualTo(1)

        // The backfilled accrual is untouched and falls into the next period — the right answer: it
        // was not part of the credit the ledger booked.
        val byDate = accrualsOf(accountId).associate { it.accrualDate to it.status }
        assertThat(byDate[LocalDate.of(2026, 1, 18)]).isEqualTo(AccrualStatus.CAPITALIZED)
        assertThat(byDate[LocalDate.of(2026, 1, 17)]).isEqualTo(AccrualStatus.ACCRUING)
    }

    @Test
    fun `a claim stuck by a ledger outage is recovered by a plain retry, with no double credit`() {
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))

        // The ledger is down: the post fails with NOTHING booked, leaving the set claimed and no
        // journal at all — the state an operator would find. It must not be a wedge.
        ledger.failNextPost("ledger unavailable")
        assertThatThrownBy { capitalize(accountId) }.hasMessageContaining("ledger unavailable")
        assertThat(statusesOf(accountId)).containsOnly(AccrualStatus.CAPITALIZING)
        assertThat(ledger.booked()).isEmpty()

        val cap = capitalize(accountId)

        assertThat(cap.grossAmount).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(ledger.booked()).hasSize(1)
        assertThat(statusesOf(accountId)).containsOnly(AccrualStatus.CAPITALIZED)
    }

    @Test
    fun `a claim held for another period end is refused instead of minting a second key`() {
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))
        ledger.crashAfterNextBooking("simulated crash")
        assertThatThrownBy { capitalize(accountId) }.hasMessageContaining("simulated crash")

        // periodTo is part of the idempotency key. Completing this claim to February would derive a
        // DIFFERENT key, so the ledger would not recognise the replay and would book a SECOND
        // journal for interest January's journal already credited.
        assertThatThrownBy { capitalize(accountId, LocalDate.of(2026, 2, 20)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already CAPITALIZING")
            .hasMessageContaining("2026-01-20")

        assertThat(ledger.booked()).hasSize(1)
        assertThat(capRowCount(accountId)).isZero()
        // ...and the documented recovery — retry with the period it was claimed for — still works.
        assertThat(capitalize(accountId).grossAmount).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(ledger.booked()).hasSize(1)
    }

    // --- Finding 3: negative gross ---------------------------------------------------------------

    @Test
    fun `a negative rate is refused loudly - no capitalization row, no journal`() {
        val accountId = UUID.randomUUID()
        // Reachable end-to-end, not contrived: annual_rate is NUMERIC(10,6) with no CHECK, the DTO
        // has no @DecimalMin, and createConfig passes it straight through.
        val configId = persistConfig(NEGATIVE_PRODUCT, BigDecimal("-0.365000"))
        val accrual = accrue(accountId, NEGATIVE_PRODUCT, BigDecimal("1000.00"))
        assertThat(accrual.accruedAmount.signum()).`as`("a negative rate really does accrue negative interest")
            .isNegative()
        assertThat(configId).isNotNull

        assertThatThrownBy {
            VertxContextSupport.subscribeAndAwait<InterestCapitalization> {
                service.capitalize(accountId, NEGATIVE_PRODUCT, periodTo)
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("NEGATIVE gross")

        // The old guard (`if (gross.signum() <= 0) return Uni.item(Unit)`) skipped the GL and let the
        // capitalization row commit anyway: the row said the customer had been CHARGED while the GL
        // recorded nothing at all. Its KDoc cited V6's `WHERE total_accrued <> 0` as authority, but
        // V6 excludes only ZERO — it treats a negative capitalization as money-bearing and
        // constrains it, i.e. it refutes the guard rather than justifying it.
        assertThat(ledger.booked()).isEmpty()
        assertThat(capRowCount(accountId)).isZero()
        // Refused before the claim, so the accruals stay claimable: fix the rate, re-run.
        assertThat(statusesOf(accountId)).containsOnly(AccrualStatus.ACCRUING)
    }

    @Test
    fun `a zero-gross period books no journal but still records the capitalization`() {
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "0.000000", LocalDate.of(2026, 1, 18))

        val cap = capitalize(accountId)

        // Zero is NOT negative: there is simply nothing to recognize, and every leg would be zero
        // while the ledger requires >=2 lines each with amount > 0.
        assertThat(ledger.booked()).isEmpty()
        assertThat(cap.grossAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(capRowCount(accountId)).isEqualTo(1)
        assertThat(statusesOf(accountId)).containsOnly(AccrualStatus.CAPITALIZED)
    }

    @Test
    fun `claim freezes the tax profile and it survives a round-trip through the DB (issue #1355)`() {
        // The persistence half of #1355: capitalize()'s claim now snapshots the resolved tax profile
        // onto the claimed accrual rows (V13), so a retry replays it instead of re-resolving. Prove the
        // snapshot actually round-trips through the new columns + mapper + claim SQL against a real DB —
        // a non-default profile persisted at claim comes back byte-for-byte on findClaimedForCapitalization.
        val accountId = UUID.randomUUID()
        persistAccrual(accountId, "100.000000", LocalDate.of(2026, 1, 18))
        val ids = accrualsOf(accountId).map { it.id }
        // A non-default profile so every field is exercised (not the FAIL_SAFE_DEFAULT the mapper could
        // reproduce by accident): a non-resident with a treaty rate and a non-cooperating-state flag.
        val frozen = TaxProfile(
            taxpayerType = TaxpayerType.INDIVIDUAL,
            residency = TaxResidency.NON_RESIDENT,
            treatyRate = BigDecimal("0.1000"),
            nonCooperatingState = true,
            exemptCode = "TREATY-CZ-DE",
        )

        VertxContextSupport.subscribeAndAwait { accrualRepo.claimForCapitalization(ids, periodTo, frozen) }

        val claimed = VertxContextSupport.subscribeAndAwait {
            accrualRepo.findClaimedForCapitalization(accountId, PRODUCT)
        }!!
        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().claimedTaxProfile).isEqualTo(frozen)
        assertThat(claimed.single().claimedPeriodTo).isEqualTo(periodTo)
    }

    // --- Helpers ---------------------------------------------------------------------------------

    // Every reactive call below runs through VertxContextSupport, never `await().indefinitely()`:
    // capitalize() reaches reactive Panache, which needs a Vert.x duplicated context. Blocking the
    // JUnit thread on the Uni instead fails with "No current Vertx context found" before the code
    // under test is ever exercised — the same reason CapitalizationTransactionIT uses this wrapper.
    private fun capitalize(accountId: UUID, to: LocalDate = periodTo): InterestCapitalization =
        VertxContextSupport.subscribeAndAwait { service.capitalize(accountId, PRODUCT, to) }!!

    private fun accrue(accountId: UUID, productId: String, balance: BigDecimal) =
        VertxContextSupport.subscribeAndAwait {
            accrualService.accrue(
                AccrualRequest(
                    accountId = accountId,
                    productId = productId,
                    balance = balance,
                    currency = "CZK",
                    accrualDate = LocalDate.of(2026, 1, 18),
                ),
            )
        }

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

    /** interest_accruals.config_id is a real FK, so every accrual needs a rate config to point at. */
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
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        }
        VertxContextSupport.subscribeAndAwait { sf.withTransaction { s -> s.persist(entity) } }
    }

    private fun accrualsOf(accountId: UUID): List<InterestAccrualEntity> = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s ->
            s.createQuery("FROM InterestAccrualEntity WHERE accountId = :a", InterestAccrualEntity::class.java)
                .setParameter("a", accountId).resultList
        }
    }!!

    private fun statusesOf(accountId: UUID): List<AccrualStatus> = accrualsOf(accountId).map { it.status }

    private fun capRowsOf(accountId: UUID): List<InterestCapitalizationEntity> = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s ->
            s.createQuery(
                "FROM InterestCapitalizationEntity WHERE accountId = :a",
                InterestCapitalizationEntity::class.java,
            ).setParameter("a", accountId).resultList
        }
    }!!

    private fun capRowCount(accountId: UUID): Int = capRowsOf(accountId).size

    private fun persistedCap(id: UUID): InterestCapitalizationEntity = VertxContextSupport.subscribeAndAwait {
        sf.withSession { s -> s.find(InterestCapitalizationEntity::class.java, id) }
    }!!

    private companion object {
        private const val PRODUCT = "SAVINGS_CZK"
        private const val NEGATIVE_PRODUCT = "SAVINGS_CZK_NEGATIVE_RATE"
    }
}
