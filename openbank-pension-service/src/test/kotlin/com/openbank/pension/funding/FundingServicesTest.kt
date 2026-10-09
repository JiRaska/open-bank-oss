// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.application.port.out.ClaimReceiptLine
import com.openbank.pension.application.port.out.ParticipantNotificationKind
import com.openbank.pension.application.usecase.ReceiptOutcome
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.domain.contribution.EmployerBatch
import com.openbank.pension.domain.contribution.EmployerBatchLine
import com.openbank.pension.domain.contribution.EmployerLineOutcome
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.domain.contribution.UnmatchedReason
import com.openbank.pension.domain.contribution.UnmatchedStatus
import com.openbank.pension.domain.incentive.ClaimBatchStatus
import com.openbank.pension.domain.incentive.ClaimStatus
import com.openbank.pension.domain.incentive.ClawbackKind
import com.openbank.pension.e2e.support.StateAgencySimulator
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class FundingServicesTest {

    private val f = InMemoryFunding()

    private fun payment(
        id: String,
        amount: String,
        reference: String?,
        date: LocalDate = LocalDate.of(2026, 1, 15),
        currency: String = "CZK",
    ) = IncomingPayment(id, BigDecimal(amount), currency, date, reference, ContributionChannel.STANDING_ORDER)

    @Test
    fun `a payment quoting the contract reference is credited once and subscribed once`(): Unit = runBlocking {
        val c = f.contract()
        val ref = f.contributionService.paymentReference(c.contractId)
        val first = f.contributionService.receive(payment("pay-1", "1700", ref))
        val again = f.contributionService.receive(payment("pay-1", "1700", ref))
        assertThat(first).isInstanceOf(ReceiptOutcome.Credited::class.java)
        assertThat(again).isInstanceOf(ReceiptOutcome.Duplicate::class.java)
        assertThat(f.contributionRows).hasSize(1)
        assertThat(f.subscriptions).containsExactly("contribution:pay-1")
        assertThat(f.contributionRows.single().subscriptionOrderId).isEqualTo("order-contribution:pay-1")
    }

    @Test
    fun `unattributable payments are parked with the reason, idempotently`(): Unit = runBlocking {
        val suspended = f.contract(status = "DRAFT")
        val ref = f.contributionService.paymentReference(suspended.contractId)
        val noRef = f.contributionService.receive(payment("a", "100", null)) as ReceiptOutcome.Unmatched
        val unknown = f.contributionService.receive(payment("b", "100", "999")) as ReceiptOutcome.Unmatched
        val notAccepting = f.contributionService.receive(payment("c", "100", ref)) as ReceiptOutcome.Unmatched
        val wrongCurrency = f.contract().let {
            f.contributionService.receive(
                payment("d", "100", f.contributionService.paymentReference(it.contractId), currency = "EUR"),
            )
        } as ReceiptOutcome.Unmatched
        f.contributionService.receive(payment("a", "100", null))
        assertThat(listOf(noRef, unknown, notAccepting, wrongCurrency).map { it.unmatched.reason }).containsExactly(
            UnmatchedReason.NO_REFERENCE,
            UnmatchedReason.UNKNOWN_REFERENCE,
            UnmatchedReason.CONTRACT_NOT_ACCEPTING,
            UnmatchedReason.CURRENCY_MISMATCH,
        )
        assertThat(f.unmatchedRows).hasSize(4)
        assertThat(f.contributionRows).isEmpty()
    }

    @Test
    fun `an operator assigns a parked payment, which credits it, and cannot resolve it twice`(): Unit = runBlocking {
        val c = f.contract()
        val parked = (f.contributionService.receive(payment("x", "800", "nope")) as ReceiptOutcome.Unmatched).unmatched
        val credited = f.contributionService.assignUnmatched(parked.id, c.contractId, "alice")
        assertThat(credited.contractId).isEqualTo(c.contractId)
        assertThat(f.unmatchedRows.getValue(parked.id).status).isEqualTo(UnmatchedStatus.ASSIGNED)
        assertThat(f.unmatchedRows.getValue(parked.id).resolvedBy).isEqualTo("alice")
        assertThatThrownBy { runBlocking { f.contributionService.returnUnmatched(parked.id, "bob") } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a failed subscription keeps the credit and the sweep places it later with the same key`(): Unit = runBlocking {
        val c = f.contract()
        val ref = f.contributionService.paymentReference(c.contractId)
        f.fundDown = true
        f.contributionService.receive(payment("p", "1000", ref))
        assertThat(f.contributionRows.single().subscriptionOrderId).isNull()
        f.fundDown = false
        assertThat(f.contributionService.placeMissingSubscriptions(listOf(c.contractId))).isEqualTo(1)
        assertThat(f.contributionService.placeMissingSubscriptions(listOf(c.contractId))).isEqualTo(0)
        assertThat(f.subscriptions).containsExactly("contribution:p")
    }

    @Test
    fun `an employer batch credits matched lines, parks the rest, and is idempotent per line`(): Unit = runBlocking {
        val employer = UUID.randomUUID().also { f.verifiedEmployers += it }
        val c1 = f.contract()
        val c2 = f.contract()
        val r1 = f.contributionService.paymentReference(c1.contractId)
        val r2 = f.contributionService.paymentReference(c2.contractId)
        f.contributionService.enrolEmployer(c1.contractId, employer)
        f.contributionService.enrolEmployer(c2.contractId, employer)
        val batch = EmployerBatch(
            employer,
            payment("emp-1", "3500", null),
            listOf(
                EmployerBatchLine(r1, BigDecimal("1000")),
                EmployerBatchLine(r2, BigDecimal("2000")),
                EmployerBatchLine("bogus", BigDecimal("500")),
            ),
        )
        val first = f.contributionService.receiveEmployerBatch(batch)
        val second = f.contributionService.receiveEmployerBatch(batch)
        assertThat(
            first.map {
                it.outcome
            },
        ).containsExactly(EmployerLineOutcome.CREDITED, EmployerLineOutcome.CREDITED, EmployerLineOutcome.UNMATCHED)
        assertThat(
            second.map {
                it.outcome
            },
        ).containsExactly(EmployerLineOutcome.DUPLICATE, EmployerLineOutcome.DUPLICATE, EmployerLineOutcome.UNMATCHED)
        assertThat(f.contributionRows).allMatch {
            it.source == ContributionSource.EMPLOYER &&
                it.employerPartyId == employer
        }
        assertThat(f.unmatchedRows.values.single().reason).isEqualTo(UnmatchedReason.EMPLOYER_LINE)
    }

    @Test
    fun `an employer batch that does not reconcile, or from an unverified employer, is refused whole`(): Unit =
        runBlocking {
            assertThatThrownBy {
                EmployerBatch(
                    UUID.randomUUID(),
                    payment("e", "100", null),
                    listOf(EmployerBatchLine("1", BigDecimal("99"))),
                )
            }
                .isInstanceOf(IllegalArgumentException::class.java)
            val c = f.contract()
            val batch =
                EmployerBatch(
                    UUID.randomUUID(),
                    payment("e", "100", null),
                    listOf(EmployerBatchLine(f.contributionService.paymentReference(c.contractId), BigDecimal("100"))),
                )
            assertThatThrownBy {
                runBlocking { f.contributionService.receiveEmployerBatch(batch) }
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(f.contributionRows).isEmpty()
        }

    @Test
    fun `the monthly run claims, files, reconciles, credits the state money and feeds the clawback ledger`(): Unit =
        runBlocking {
            val c = f.contract()
            val dip = f.contract(productLine = "DIP")
            val ref = f.contributionService.paymentReference(c.contractId)
            f.contributionService.receive(payment("jan-1", "1000", ref, LocalDate.of(2026, 1, 5)))
            f.contributionService.receive(payment("jan-2", "1000", ref, LocalDate.of(2026, 1, 25)))
            f.contributionService.receive(
                payment(
                    "dip",
                    "5000",
                    f.contributionService.paymentReference(dip.contractId),
                    LocalDate.of(2026, 1, 5),
                ),
            )

            val run = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1))
            val rerun = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1))
            assertThat(run.claimsCreated).isEqualTo(1)
            assertThat(rerun.claimsCreated).isZero()
            assertThat(rerun.batches).isEmpty()
            val batch = run.batches.single()
            val claim = f.claimRows.values.single()
            assertThat(claim.claimedAmount).isEqualByComparingTo("340.00")
            assertThat(claim.status).isEqualTo(ClaimStatus.SUBMITTED)
            assertThat(batch.payload).contains(ref).contains(claim.id.toString())

            val reconciled = f.incentiveService.reconcileReceiptFile(
                batch.id,
                StateAgencySimulator.receipt(batch.payload),
            )
            assertThat(reconciled.status).isEqualTo(ClaimBatchStatus.RECONCILED)
            val state = f.contributionRows.single { it.source == ContributionSource.STATE }
            assertThat(state.amount).isEqualByComparingTo("340.00")
            assertThat(f.subscriptions).contains("contribution:incentive:${claim.id}")
            // re-applying the same receipt changes nothing
            f.incentiveService.reconcile(batch.id, listOf(ClaimReceiptLine(claim.id, true, BigDecimal("340.00"), null)))
            assertThat(f.ledgerRows).hasSize(1)
            // #12379: the participant hears once that the incentive arrived — not again on a re-applied receipt.
            assertThat(f.notifier.sent.map { it.kind }).containsExactly(ParticipantNotificationKind.INCENTIVE_RECEIVED)
            assertThat(f.notifier.sent.single().partyId).isEqualTo(c.participantPartyId)
            assertThat(f.notifier.sent.single().variables["amount"]).isEqualTo("340.00")

            val preview = f.incentiveService.clawbackPreview(c.contractId, LocalDate.of(2026, 6, 1))
            assertThat(
                preview.single {
                    it.kind == ClawbackKind.RETURN_TO_AGENCY
                }.amount,
            ).isEqualByComparingTo("340.00")

            f.incentiveService.returnClaim(claim.id)
            assertThat(f.notifier.sent.map { it.kind }).containsExactly(
                ParticipantNotificationKind.INCENTIVE_RECEIVED,
                ParticipantNotificationKind.INCENTIVE_RETURNED,
            )
            assertThat(f.incentiveService.status(c.contractId).balances.single().net).isEqualByComparingTo("0")
            assertThat(
                f.incentiveService.clawbackPreview(c.contractId, LocalDate.of(2026, 6, 1)).filter {
                    it.kind ==
                        ClawbackKind.RETURN_TO_AGENCY
                },
            ).isEmpty()
        }

    @Test
    fun `an agency rejection is recorded with its reason and credits nothing`(): Unit = runBlocking {
        val c = f.contract()
        f.contributionService.receive(
            payment("p", "700", f.contributionService.paymentReference(c.contractId), LocalDate.of(2026, 1, 9)),
        )
        val batch = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1)).batches.single()
        val claim = f.claimRows.values.single()
        assertThatThrownBy {
            runBlocking {
                f.incentiveService.reconcile(
                    batch.id,
                    listOf(ClaimReceiptLine(UUID.randomUUID(), true, BigDecimal.ONE, null)),
                )
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        f.incentiveService.reconcileReceiptFile(
            batch.id,
            StateAgencySimulator.receipt(batch.payload, reject = mapOf(claim.id.toString() to "OLD_AGE_PENSIONER")),
        )
        assertThat(f.claimRows.getValue(claim.id).status).isEqualTo(ClaimStatus.REJECTED)
        assertThat(f.claimRows.getValue(claim.id).rejectionReason).startsWith("OLD_AGE_PENSIONER:")
        assertThat(f.ledgerRows).isEmpty()
        assertThat(f.contributionRows.none { it.source == ContributionSource.STATE }).isTrue()
    }

    @Test
    fun `the tax year sums by source, shares one deduction cap per participant, and certifies once`(): Unit =
        runBlocking {
            val party = UUID.randomUUID()
            val older = f.contract(participant = party)
            val newer = f.contract(
                participant = party,
                productLine = "DIP",
                createdAt = java.time.Instant.parse("2025-06-01T00:00:00Z"),
            )
            val employer = UUID.randomUUID().also { f.verifiedEmployers += it }
            val r1 = f.contributionService.paymentReference(older.contractId)
            val r2 = f.contributionService.paymentReference(newer.contractId)
            f.contributionService.receive(payment("o", "50400", r1, LocalDate.of(2025, 3, 1)))
            // ^ 30000 above the DPS threshold
            f.contributionService.receive(payment("n", "30000", r2, LocalDate.of(2025, 3, 1)))
            f.contributionService.enrolEmployer(newer.contractId, employer)
            f.contributionService.receiveEmployerBatch(
                EmployerBatch(
                    employer,
                    payment("e", "60000", null, LocalDate.of(2025, 4, 1)),
                    listOf(EmployerBatchLine(r2, BigDecimal("60000"))),
                ),
            )

            val older2025 = f.incentiveService.taxSummary(older.contractId, 2025)
            val newer2025 = f.incentiveService.taxSummary(newer.contractId, 2025)
            assertThat(older2025.deductibleAmount).isEqualByComparingTo("30000.00")
            assertThat(newer2025.deductibleAmount).isEqualByComparingTo("18000.00")
            assertThat(newer2025.employerContributions).isEqualByComparingTo("60000.00")
            assertThat(newer2025.employerExempt).isEqualByComparingTo("50000.00")
            assertThat(newer2025.employerTaxable).isEqualByComparingTo("10000.00")

            f.incentiveService.declareExternalCapUsage(
                older.contractId,
                2025,
                mapOf(
                    "retirement-products-deduction" to BigDecimal("40000"),
                ),
            )
            assertThat(
                f.incentiveService.taxSummary(older.contractId, 2025).deductibleAmount,
            ).isEqualByComparingTo("8000.00")

            val cert = f.incentiveService.issueCertificate(older.contractId, 2025)
            assertThat(cert.certificateDocumentId).isEqualTo("doc-${older.contractId}-2025")
            assertThat(f.incentiveService.issueCertificate(older.contractId, 2025)).isEqualTo(cert)
            assertThatThrownBy {
                runBlocking {
                    f.incentiveService.declareExternalCapUsage(
                        older.contractId,
                        2025,
                        mapOf(
                            "x" to BigDecimal.ONE,
                        ),
                    )
                }
            }
                .isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { runBlocking { f.incentiveService.issueCertificate(older.contractId, 2026) } }
                .isInstanceOf(IllegalStateException::class.java)
        }

    @Test
    fun `an employer cannot credit a contract it is not enrolled on, even with a valid reference`(): Unit =
        runBlocking {
            val mine = UUID.randomUUID().also { f.verifiedEmployers += it }
            val stranger = f.contract()
            val ref = f.contributionService.paymentReference(stranger.contractId)
            val result = f.contributionService.receiveEmployerBatch(
                EmployerBatch(mine, payment("x-1", "1000", null), listOf(EmployerBatchLine(ref, BigDecimal("1000")))),
            )
            assertThat(result.single().outcome).isEqualTo(EmployerLineOutcome.UNMATCHED)
            assertThat(f.unmatchedRows.values.single().reason).isEqualTo(UnmatchedReason.EMPLOYER_NOT_AUTHORISED)
            assertThat(f.contributionRows).isEmpty()
        }

    @Test
    fun `amounts out of bounds or with sub-cent precision are refused before anything is stored`() {
        listOf("0", "-1", "0.001", "1000000000", "12.345").forEach { bad ->
            assertThatThrownBy { payment("b-$bad", bad, "1") }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy {
            payment("c", "10", "1", currency = "czk")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { payment("x".repeat(129), "10", "1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            EmployerBatchLine("1", BigDecimal("0.001"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a claim already filed by a concurrent run is never filed a second time`(): Unit = runBlocking {
        val c = f.contract()
        f.contributionService.receive(
            payment("p", "1700", f.contributionService.paymentReference(c.contractId), LocalDate.of(2026, 1, 9)),
        )
        f.incentiveService.generateClaims(YearMonth.of(2026, 1))
        val claim = f.claimRows.values.single()
        // A concurrent run files the claim between our read and our filing.
        val racing = com.openbank.pension.domain.incentive.ClaimBatch(
            UUID.randomUUID(),
            "cz-mf-state-contribution-v1",
            YearMonth.of(2026, 1),
            listOf(claim.id),
            "other",
            null,
            ClaimBatchStatus.SUBMITTED,
            f.clock.instant(),
        )
        assertThat(f.batches.fileAtomically(racing, f.clock.instant())).isTrue()
        assertThat(f.batches.fileAtomically(racing.copy(id = UUID.randomUUID()), f.clock.instant())).isFalse()
        val (filed, _) = f.incentiveService.submitPending()
        assertThat(filed).isEmpty()
        assertThat(f.batchRows).hasSize(1)
    }

    @Test
    fun `a first payment to a signed onboarding is credited and signals onboarding, never activating itself`(): Unit =
        runBlocking {
            val c = f.contract(status = "PENDING_ACTIVATION")
            f.awaitingOnboarding += c.contractId
            val outcome = f.contributionService.receive(
                payment("first", "1700", f.contributionService.paymentReference(c.contractId)),
            )
            assertThat(outcome).isInstanceOf(ReceiptOutcome.Credited::class.java)
            assertThat(f.activationSignals).containsExactly(c.contractId)
            assertThat(f.contracts.getValue(c.contractId).status)
                .describedAs("only the onboarding workflow activates (signature, KID, cooling-off)")
                .isEqualTo("PENDING_ACTIVATION")
        }

    /**
     * ADR-0334 S8 security fix: S3's default activation adapter applied S1's activate transition on
     * the first payment, so ANY payment quoting the reference activated a PENDING_ACTIVATION
     * contract whose onboarding was never signed (no KID, no SCA, no cooling-off). Now such money
     * is parked and nothing is activated or subscribed.
     */
    @Test
    fun `a payment to a pending contract with no signed onboarding is parked and activates nothing`(): Unit =
        runBlocking {
            val c = f.contract(status = "PENDING_ACTIVATION")
            val outcome = f.contributionService.receive(
                payment("unsigned", "1700", f.contributionService.paymentReference(c.contractId)),
            )
            assertThat(outcome).isInstanceOf(ReceiptOutcome.Unmatched::class.java)
            assertThat((outcome as ReceiptOutcome.Unmatched).unmatched.reason)
                .isEqualTo(UnmatchedReason.CONTRACT_NOT_ACCEPTING)
            assertThat(f.activationSignals).isEmpty()
            assertThat(f.subscriptions).isEmpty()
            assertThat(f.contracts.getValue(c.contractId).status).isEqualTo("PENDING_ACTIVATION")
            // An operator cannot force it in either.
            val parked = outcome.unmatched.id
            assertThatThrownBy {
                runBlocking { f.contributionService.assignUnmatched(parked, c.contractId, "ops") }
            }.isInstanceOf(IllegalStateException::class.java)
        }

    @Test
    fun `transferred-in funds are booked as TRANSFER_IN once, without a second subscription`(): Unit = runBlocking {
        val c = f.contract()
        val transfer = UUID.randomUUID()
        f.contributionService.bookTransferIn(
            c.contractId,
            transfer,
            BigDecimal("80000"),
            "CZK",
            LocalDate.of(2026, 1, 3),
        )
        f.contributionService.bookTransferIn(
            c.contractId,
            transfer,
            BigDecimal("80000"),
            "CZK",
            LocalDate.of(2026, 1, 3),
        )
        val year = f.incentiveService.taxSummary(c.contractId, 2026)
        assertThat(year.transferIn).isEqualByComparingTo("80000.00")
        assertThat(year.participantContributions).isEqualByComparingTo("0")
        assertThat(f.subscriptions).isEmpty()
        assertThat(f.contributionService.placeMissingSubscriptions(listOf(c.contractId))).isZero()
    }

    @Test
    fun `the clawback balance reports the real received incentive and settlement is idempotent`(): Unit = runBlocking {
        val c = f.contract()
        val ref = f.contributionService.paymentReference(c.contractId)
        f.contributionService.receive(payment("p", "1700", ref, LocalDate.of(2026, 1, 9)))
        val batch = f.incentiveService.runMonthlyClaims(YearMonth.of(2026, 1)).batches.single()
        f.incentiveService.reconcileReceiptFile(batch.id, StateAgencySimulator.receipt(batch.payload))

        val balance = f.incentiveService.clawbackBalance(c.contractId, LocalDate.of(2026, 6, 1))
        assertThat(balance.stateIncentivesToReturn).isEqualByComparingTo("340.00")
        assertThat(balance.stateIncentivesReceived).isEqualByComparingTo("340.00")
        assertThat(balance.ownContributionsNotDeducted).isEqualByComparingTo("1700.00")

        f.incentiveService.settleClawback(c.contractId, BigDecimal("340.00"), "exit-1")
        f.incentiveService.settleClawback(c.contractId, BigDecimal("340.00"), "exit-1")
        assertThat(
            f.ledgerRows.filter {
                it.kind == com.openbank.pension.domain.incentive.LedgerEntryKind.RETURNED
            },
        ).hasSize(1)
        assertThat(f.incentiveService.clawbackBalance(c.contractId, LocalDate.of(2026, 6, 1)).stateIncentivesToReturn)
            .isEqualByComparingTo("0")
        assertThatThrownBy { runBlocking { f.incentiveService.settleClawback(c.contractId, BigDecimal.ONE, "exit-2") } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
