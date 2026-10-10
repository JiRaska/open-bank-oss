// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.annuity

import com.openbank.pension.application.exit.AnnuityPlacement
import com.openbank.pension.application.exit.AnnuityStepPendingException
import com.openbank.pension.application.exit.ExitForbiddenException
import com.openbank.pension.application.exit.InstructionStatus
import com.openbank.pension.application.exit.PaymentInstruction
import com.openbank.pension.application.exit.PaymentInstructionRepository
import com.openbank.pension.application.exit.PaymentOrder
import com.openbank.pension.application.exit.PayoutPaymentPort
import com.openbank.pension.application.exit.PayoutRequestRepository
import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.port.out.Redemption
import com.openbank.pension.application.port.out.Valuation
import com.openbank.pension.application.usecase.PensionContractService
import com.openbank.pension.domain.annuity.AnnuityCompensation
import com.openbank.pension.domain.annuity.AnnuityOffer
import com.openbank.pension.domain.annuity.AnnuityProvider
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.domain.annuity.AnnuityProviderTerms
import com.openbank.pension.domain.annuity.AnnuityPurchase
import com.openbank.pension.domain.annuity.AnnuityPurchaseStatus
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.ApprovedPartner
import com.openbank.pension.domain.annuity.RefusedPremiumDestination
import com.openbank.pension.domain.exit.PayoutQuote
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.exit.TaxBase
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.testsupport.ContractFixtures
import com.openbank.pension.testsupport.ProviderFixtures
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AnnuityMarketplaceServiceTest {

    private val clock = Clock.fixed(Instant.parse("2030-03-01T10:00:00Z"), ZoneOffset.UTC)
    private val party = UUID.randomUUID()
    private val me = Caller.customer(party)
    private val premium = BigDecimal("1000000.00")
    private val iban = "CZ6508000000192000145399"

    // ---- quoting ----

    @Test
    fun `every eligible partner is asked in parallel and a slow or failing one does not sink the round`(): Unit =
        runBlocking {
            val w = world()
            w.adapter.behaviour["alpha"] = Behaviour(delayMs = 800)
            w.adapter.behaviour["beta"] = Behaviour(delayMs = 800)
            w.adapter.behaviour["slow"] = Behaviour(delayMs = 5_000)
            w.adapter.behaviour["broken"] = Behaviour(fail = true)
            listOf("alpha", "beta", "slow", "broken").forEach { w.activate(it) }
            w.activate("abroad", jurisdictions = setOf("SK"))

            val started = System.nanoTime()
            val purchase = w.service.requestOffers(
                me,
                w.contract.id,
                w.payout.id,
                AnnuityPreferences(setOf(AnnuityType.LIFELONG)),
            )
            val elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()

            assertThat(purchase.offers.map { it.partnerId }).containsExactlyInAnyOrder("alpha", "beta")
            assertThat(purchase.failures.map { it.partnerId }).containsExactly("broken", "slow")
            assertThat(purchase.failures.first { it.partnerId == "slow" }.reason).contains("timed out")
            // Two 800 ms partners and a 1 s timeout: sequential would take >= 2.6 s; parallel about 1 s.
            assertThat(elapsedMs).isLessThan(2_300)
            // An ineligible partner is never asked.
            assertThat(w.adapter.quoted).doesNotContain("abroad")
        }

    @Test
    fun `an offer that does not answer the request is dropped and reported, never presented`(): Unit = runBlocking {
        val w = world()
        w.activate("alpha")
        w.adapter.behaviour["alpha"] = Behaviour(premiumOverride = BigDecimal("999999.00"))
        val purchase = w.service.requestOffers(me, w.contract.id, w.payout.id, AnnuityPreferences())
        assertThat(purchase.offers).isEmpty()
        assertThat(purchase.failures.single().reason).contains("did not match")
    }

    @Test
    fun `offers are presented in the one disclosed partner-neutral order`() {
        val now = clock.instant()
        fun o(p: String, t: AnnuityType, m: String) = AnnuityOffer(
            "$p-$t", p, p, t, premium, "CZK", BigDecimal(m),
            termMonths = if (t == AnnuityType.FIXED_TERM) 120 else null, validUntil = now, illustrative = true,
        )
        val sorted = AnnuityMarketplaceService.present(
            listOf(
                o("zeta", AnnuityType.FIXED_TERM, "9000"),
                o("beta", AnnuityType.LIFELONG, "4000"),
                o("alpha", AnnuityType.LIFELONG, "4000"),
                o("gamma", AnnuityType.LIFELONG, "4100"),
            ),
        )
        assertThat(sorted.map { it.partnerId }).containsExactly("gamma", "alpha", "beta", "zeta")
    }

    // ---- selection ----

    @Test
    fun `selection needs an SCA challenge signed over exactly that offer`(): Unit = runBlocking {
        val w = world()
        w.activate("alpha")
        val offered = w.service.requestOffers(
            me,
            w.contract.id,
            w.payout.id,
            AnnuityPreferences(setOf(AnnuityType.LIFELONG)),
        )
        val offer = offered.offers.single()
        w.sca.accept = false
        assertThatThrownBy {
            runBlocking { w.service.select(me, w.contract.id, w.payout.id, "alpha", offer.offerId, "sca-1") }
        }.isInstanceOf(ExitForbiddenException::class.java)
        w.sca.accept = true
        val selected = w.service.select(me, w.contract.id, w.payout.id, "alpha", offer.offerId, "sca-2")
        assertThat(selected.status).isEqualTo(AnnuityPurchaseStatus.SELECTED)
        assertThat(w.sca.signed.last()).isEqualTo(offered.selectionHash(offer))
    }

    @Test
    fun `a confirmation needs a binding selection with an active partner`(): Unit = runBlocking {
        val w = world()
        assertThatThrownBy { runBlocking { w.service.requireBindingSelection(w.payout.id, premium) } }
            .isInstanceOf(IllegalStateException::class.java)
        w.select("alpha")
        w.service.requireBindingSelection(w.payout.id, premium)
        w.providers.rows["alpha"] = w.providers.rows.getValue("alpha").disable(clock.instant())
        assertThatThrownBy { runBlocking { w.service.requireBindingSelection(w.payout.id, premium) } }
            .hasMessageContaining("no longer active")
    }

    // ---- placement ----

    @Test
    fun `happy path - application, ONE premium to the partner, policy issued - and a retry repeats nothing`(): Unit =
        runBlocking {
            val w = world()
            w.select("alpha")
            val payout = w.inPayment()
            val outcome = w.service.place(payout, w.contract)
            assertThat(outcome).isInstanceOf(AnnuityPlacement.Issued::class.java)
            assertThat((outcome as AnnuityPlacement.Issued).policy.insurerRef).isEqualTo("alpha")
            w.service.place(payout, w.contract)
            assertThat(w.payments.orders).hasSize(1)
            val order = w.payments.orders.single()
            assertThat(order.creditorIban).isEqualTo(PARTNER_IBAN)
            assertThat(order.amount).isEqualByComparingTo(premium)
            assertThat(w.adapter.applications).hasSize(1)
        }

    @Test
    fun `a partner that refuses the application never receives a premium`(): Unit = runBlocking {
        val w = world()
        w.select("alpha")
        w.adapter.behaviour["alpha"] = Behaviour(refuseApplication = true)
        val outcome = w.service.place(w.inPayment(), w.contract)
        assertThat(outcome).isEqualTo(AnnuityPlacement.ReturnedToContract)
        assertThat(w.payments.orders).isEmpty()
        assertThat(w.fund.subscribed).containsExactly(premium)
    }

    @Test
    fun `premium not sent - the application is cancelled and the money goes back to the contract per pack`(): Unit =
        runBlocking {
            val w = world()
            w.select("alpha")
            w.payments.reject = true
            val outcome = w.service.place(w.inPayment(), w.contract)
            assertThat(outcome).isEqualTo(AnnuityPlacement.ReturnedToContract)
            assertThat(w.adapter.cancelled).containsExactly(PartnerCancellationReason.PREMIUM_NOT_SENT)
            val stored = w.purchases.rows.getValue(w.payout.id)
            assertThat(stored.status).isEqualTo(AnnuityPurchaseStatus.FAILED)
            assertThat(stored.policyRef).isNull()
            assertThat(stored.compensation).isEqualTo(AnnuityCompensation.RETURNED_TO_CONTRACT)
            // Retried: compensation is not repeated.
            w.service.place(w.inPayment(), w.contract)
            assertThat(w.fund.subscribed).hasSize(1)
        }

    @Test
    fun `a refusal after the premium waits for the refund, then pays the client when the pack says so`(): Unit =
        runBlocking {
            val w = world(destination = RefusedPremiumDestination.CLIENT)
            w.select("alpha")
            w.adapter.behaviour["alpha"] = Behaviour(refusePolicy = true, refundReady = false)
            assertThatThrownBy { runBlocking { w.service.place(w.inPayment(), w.contract) } }
                .isInstanceOf(AnnuityStepPendingException::class.java)
            w.adapter.behaviour["alpha"] = Behaviour(refusePolicy = true, refundReady = true)
            val outcome = w.service.place(w.inPayment(), w.contract)
            assertThat(outcome).isInstanceOf(AnnuityPlacement.ReturnedToClient::class.java)
            assertThat(w.payments.orders.map { it.creditorIban }).containsExactly(PARTNER_IBAN, iban)
            assertThat(w.fund.subscribed).isEmpty()
        }

    @Test
    fun `cooling-off cancellation needs SCA, reaches the partner, and refunds the client`(): Unit = runBlocking {
        val w = world()
        w.select("alpha")
        w.service.place(w.inPayment(), w.contract)
        w.sca.accept = false
        assertThatThrownBy { runBlocking { w.service.cancel(me, w.contract.id, w.payout.id, "sca-c") } }
            .isInstanceOf(ExitForbiddenException::class.java)
        assertThat(w.adapter.cancelled).isEmpty()
        w.sca.accept = true
        val cancelled = w.service.cancel(me, w.contract.id, w.payout.id, "sca-c2")
        assertThat(cancelled.status).isEqualTo(AnnuityPurchaseStatus.CANCELLED)
        assertThat(cancelled.compensation).isEqualTo(AnnuityCompensation.RETURNED_TO_CLIENT)
        assertThat(w.adapter.cancelled).containsExactly(PartnerCancellationReason.COOLING_OFF)
        assertThat(w.payments.orders.last().creditorIban).isEqualTo(iban)
    }

    // ---- four-eyes: only the approved, pinned version is ever used (security review) ----

    @Test
    fun `a pending edit is invisible - the quote is produced under the old approved terms`(): Unit = runBlocking {
        val w = world()
        w.activate("alpha")
        w.proposeEdit("alpha")
        val offered = w.service.requestOffers(
            me,
            w.contract.id,
            w.payout.id,
            AnnuityPreferences(setOf(AnnuityType.LIFELONG)),
        )
        assertThat(w.adapter.quotedIbans).containsExactly(PARTNER_IBAN)
        assertThat(offered.offers.single().providerVersion).isEqualTo(1)
    }

    @Test
    fun `a quote made before an edit was approved is refused at purchase - stale, nothing is sent`(): Unit =
        runBlocking {
            val w = world()
            w.select("alpha")
            w.proposeEdit("alpha")
            w.providers.save(w.providers.rows.getValue("alpha").approveActivation("checker", clock.instant()))
            assertThatThrownBy { runBlocking { w.service.requireBindingSelection(w.payout.id, premium) } }
                .hasMessageContaining("request new offers")
            val outcome = w.service.place(w.inPayment(), w.contract)
            assertThat(outcome).isEqualTo(AnnuityPlacement.ReturnedToContract)
            assertThat(w.payments.orders).isEmpty()
            assertThat(w.adapter.applications).isEmpty()
            assertThat(w.purchases.rows.getValue(w.payout.id).failureReason).contains("stale quote")
        }

    @Test
    fun `a disabled partner is refused before any premium leaves`(): Unit = runBlocking {
        val w = world()
        w.select("alpha")
        w.providers.save(w.providers.rows.getValue("alpha").disable(clock.instant()))
        val outcome = w.service.place(w.inPayment(), w.contract)
        assertThat(outcome).isEqualTo(AnnuityPlacement.ReturnedToContract)
        assertThat(w.payments.orders).isEmpty()
    }

    @Test
    fun `disabling after the premium left never strands money - the purchase settles on its pinned snapshot`(): Unit =
        runBlocking {
            val w = world()
            w.select("alpha")
            w.adapter.behaviour["alpha"] = Behaviour(refusePolicy = true, refundReady = false)
            assertThatThrownBy { runBlocking { w.service.place(w.inPayment(), w.contract) } }
                .isInstanceOf(AnnuityStepPendingException::class.java)
            w.providers.save(w.providers.rows.getValue("alpha").disable(clock.instant()))
            w.adapter.behaviour["alpha"] = Behaviour()
            val outcome = w.service.place(w.inPayment(), w.contract)
            assertThat(outcome).isInstanceOf(AnnuityPlacement.Issued::class.java)
            assertThat(w.payments.orders).hasSize(1)
        }

    // ---------------------------------------------------------------------------------------------

    @Suppress("LongParameterList") // a test world: one handle per fake
    private inner class World(
        val service: AnnuityMarketplaceService,
        val contract: PensionContract,
        val payout: PayoutRequest,
        val adapter: FakeAdapter,
        val providers: Providers,
        val purchases: Purchases,
        val payouts: Payouts,
        val payments: Payments,
        val sca: Sca,
        val fund: Fund,
    ) {
        /** A maker proposes new terms (another premium IBAN) and requests approval; nobody approves yet. */
        suspend fun proposeEdit(id: String) {
            val current = providers.rows.getValue(id)
            providers.save(
                current.propose(
                    terms(setOf("CZ"), EDITED_IBAN),
                    "maker",
                    clock.instant(),
                ).requestActivation("maker", clock.instant()),
            )
        }

        suspend fun activate(id: String, jurisdictions: Set<String> = setOf("CZ")) {
            providers.save(
                AnnuityProvider.draft(id, terms(jurisdictions), "maker", clock.instant())
                    .requestActivation("maker", clock.instant()).approveActivation("checker", clock.instant()),
            )
        }

        suspend fun select(partner: String) {
            activate(partner)
            val offered = service.requestOffers(
                me,
                contract.id,
                payout.id,
                AnnuityPreferences(setOf(AnnuityType.LIFELONG)),
            )
            service.select(
                me,
                contract.id,
                payout.id,
                partner,
                offered.offers.first {
                    it.partnerId == partner
                }.offerId,
                "sca-sel",
            )
        }

        /** The payout as the workflow sees it at the annuity step: confirmed, redeemed, IN_PAYMENT. */
        suspend fun inPayment(): PayoutRequest {
            val current = requireNotNull(payouts.findById(payout.id))
            if (current.status == PayoutStatus.IN_PAYMENT) return current
            return payouts.save(
                current.confirm(iban, "sca-confirm", "idem", LocalDate.now(clock), clock.instant())
                    .markRedeemed(premium, clock.instant()),
            )
        }
    }

    private fun world(destination: RefusedPremiumDestination = RefusedPremiumDestination.CONTRACT): World =
        runBlocking {
            val packs = JurisdictionPackLoader.loadAll().map { pack ->
                val exit = pack.exit
                val annuity = exit?.annuity
                if (exit != null && annuity != null) {
                    pack.copy(exit = exit.copy(annuity = annuity.copy(refusedPremiumDestination = destination)))
                } else {
                    pack
                }
            }
            val registry = JurisdictionPackRegistry(packs)
            val repo = Contracts()
            val contracts =
                PensionContractService(
                    repo,
                    registry,
                    clock,
                    com.openbank.pension.infrastructure.notification.RecordingParticipantNotifier(),
                    com.openbank.pension.testsupport.RecordingSuitability(),
                    com.openbank.pension.testsupport.RecordingSca(),
                    ProviderFixtures.boundary,
                )
            val id = ContractFixtures.activeContract(
                contracts,
                repo,
                party,
                productLine = ProductLine.DPS,
                birthDate = LocalDate.parse("1965-01-01"),
                startDate = LocalDate.parse("2010-01-01"),
            )
            val contract = requireNotNull(repo.findById(id))
            val payouts = Payouts()
            val quote = PayoutQuote(
                PayoutForm.ANNUITY, premium, premium, TaxBase.NONE,
                BigDecimal.ZERO.setScale(
                    2,
                ),
                BigDecimal.ZERO.setScale(2),
                premium, "CZK", 1,
            )
            val payout = payouts.save(PayoutRequest.quote(id, party, quote, 30, clock.instant()))
            val adapter = FakeAdapter()
            val providers = Providers()
            val purchases = Purchases()
            val payments = Payments()
            val sca = Sca()
            val fund = Fund()
            val catalog = object : AnnuityAdapterCatalog {
                override fun adapterFor(kind: String) = adapter.takeIf { kind == it.kind }
                override fun kinds() = setOf(adapter.kind)
            }
            val service = AnnuityMarketplaceService(
                contracts,
                AnnuityStores(purchases, providers, payouts, Instructions()),
                AnnuityRails(catalog, sca, payments, fund),
                registry,
                clock,
                Duration.ofSeconds(1),
            )
            World(service, contract, payout, adapter, providers, purchases, payouts, payments, sca, fund)
        }

    private fun terms(jurisdictions: Set<String>, premiumIban: String = PARTNER_IBAN) = AnnuityProviderTerms(
        legalName = "Partner Life", legalEntityPartyId = UUID.randomUUID(), licenceRef = "L-1",
        licenceAuthority = "CNB",
        jurisdictions = jurisdictions, supportedTypes = AnnuityType.entries.toSet(), currency = "CZK",
        minPremium = BigDecimal("10000"), maxPremium = BigDecimal("50000000"), coolingOffDays = 30,
        premiumIban = premiumIban, adapter = "fake", effectiveFrom = LocalDate.parse("2020-01-01"),
    )

    data class Behaviour(
        val delayMs: Long = 0,
        val fail: Boolean = false,
        val premiumOverride: BigDecimal? = null,
        val refuseApplication: Boolean = false,
        val refusePolicy: Boolean = false,
        val refundReady: Boolean = true,
    )

    inner class FakeAdapter : AnnuityProviderAdapter {
        override val kind = "fake"
        val behaviour = ConcurrentHashMap<String, Behaviour>()
        val quoted = CopyOnWriteArrayList<String>()
        val applications = ConcurrentHashMap<String, String>()
        val cancelled = CopyOnWriteArrayList<PartnerCancellationReason>()

        private fun b(p: ApprovedPartner) = behaviour[p.partnerId] ?: Behaviour()

        val quotedIbans = CopyOnWriteArrayList<String>()

        override suspend fun quote(provider: ApprovedPartner, request: AnnuityQuoteRequest): List<AnnuityOffer> {
            quoted += provider.partnerId
            quotedIbans += provider.terms.premiumIban
            val b = b(provider)
            delay(b.delayMs)
            check(!b.fail) { "boom" }
            return request.types.filter { it == AnnuityType.LIFELONG }.map {
                AnnuityOffer(
                    "${provider.partnerId}-1", provider.partnerId, "x", it, b.premiumOverride ?: request.premium,
                    request.currency,
                    BigDecimal("4000"), validUntil = clock.instant().plus(Duration.ofDays(3)), illustrative = true,
                )
            }
        }

        override suspend fun purchase(provider: ApprovedPartner, application: AnnuityApplication): PartnerPolicyStatus {
            if (b(
                    provider,
                ).refuseApplication
            ) {
                return PartnerPolicyStatus("APP-R", PartnerPolicyState.REFUSED, reason = "no")
            }
            applications.putIfAbsent(application.idempotencyKey, "APP-${provider.partnerId}")
            return PartnerPolicyStatus(applications.getValue(application.idempotencyKey), PartnerPolicyState.APPLIED)
        }

        override suspend fun status(provider: ApprovedPartner, applicationRef: String): PartnerPolicyStatus {
            val b = b(provider)
            return if (b.refusePolicy) {
                PartnerPolicyStatus(
                    applicationRef,
                    PartnerPolicyState.REFUSED,
                    reason = "no",
                    refundRef = "R".takeIf {
                        b.refundReady
                    },
                )
            } else {
                PartnerPolicyStatus(
                    applicationRef,
                    PartnerPolicyState.ACTIVE,
                    policyRef = "POL-1",
                    monthlyAmount = BigDecimal("4000"),
                )
            }
        }

        override suspend fun cancel(
            provider: ApprovedPartner,
            applicationRef: String,
            reason: PartnerCancellationReason,
            idempotencyKey: String,
        ): PartnerPolicyStatus {
            cancelled += reason
            return PartnerPolicyStatus(applicationRef, PartnerPolicyState.CANCELLED, refundRef = "RF")
        }
    }

    class Providers : AnnuityProviderRepository {
        val rows = ConcurrentHashMap<String, AnnuityProvider>()
        override suspend fun save(provider: AnnuityProvider) = provider.also { rows[it.partnerId] = it }
        override suspend fun find(partnerId: String) = rows[partnerId]
        override suspend fun list(status: AnnuityProviderStatus?) =
            rows.values.filter { status == null || it.status == status }.sortedBy { it.partnerId }
    }

    class Purchases : AnnuityPurchaseRepository {
        val rows = ConcurrentHashMap<UUID, AnnuityPurchase>()
        override suspend fun save(purchase: AnnuityPurchase) = purchase.also { rows[it.id] = it }
        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun list(status: AnnuityPurchaseStatus?, limit: Int) = rows.values.toList()
    }

    class Payouts : PayoutRequestRepository {
        val rows = ConcurrentHashMap<UUID, PayoutRequest>()
        override suspend fun save(request: PayoutRequest) = request.also { rows[it.id] = it }
        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun findByContract(contractId: UUID) = rows.values.filter { it.contractId == contractId }
        override suspend fun findInPayment() = rows.values.toList()
        override suspend fun list(status: PayoutStatus?, contractId: UUID?, limit: Int) = rows.values.toList()
    }

    class Instructions : PaymentInstructionRepository {
        val rows = ConcurrentHashMap<String, PaymentInstruction>()
        override suspend fun recordIfAbsent(instruction: PaymentInstruction) =
            rows.putIfAbsent(instruction.idempotencyKey, instruction) ?: instruction
        override suspend fun markSent(idempotencyKey: String, paymentRef: String) {
            rows.computeIfPresent(idempotencyKey) { _, i ->
                i.copy(status = InstructionStatus.SENT, paymentRef = paymentRef)
            }
        }
        override suspend fun findByContract(contractId: UUID) = rows.values.filter { it.contractId == contractId }
    }

    class Payments : PayoutPaymentPort {
        val orders = CopyOnWriteArrayList<PaymentOrder>()
        var reject = false
        override suspend fun pay(order: PaymentOrder): String {
            check(!reject) { "creditor account closed" }
            orders += order
            return "PAY-${orders.size}"
        }
    }

    class Sca : ScaVerificationPort {
        var accept = true
        val operations = java.util.concurrent.CopyOnWriteArrayList<ScaOperation>()
        val signed = CopyOnWriteArrayList<String>()
        override suspend fun verify(
            partyId: UUID,
            challengeId: String,
            documentSha256: String,
            operation: ScaOperation,
        ): Boolean {
            operations += operation
            signed += documentSha256
            return accept
        }
    }

    class Fund : FundAdministrationPort {
        val subscribed = CopyOnWriteArrayList<BigDecimal>()
        override suspend fun valuation(contractId: UUID, currency: String) =
            Valuation(BigDecimal.ZERO, currency, LocalDate.now())
        override suspend fun subscribe(
            contractId: UUID,
            amount: BigDecimal,
            currency: String,
            idempotencyKey: String,
        ): String {
            subscribed += amount
            return "SUB-1"
        }
        override suspend fun redeem(contractId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String) =
            Redemption("R", amount)
        override suspend fun reverseRedemption(contractId: UUID, redemption: Redemption, currency: String) = Unit
        override suspend fun holdings(contractId: UUID) =
            com.openbank.pension.application.port.out.FundHoldings(emptyList(), emptyList())
        override suspend fun transactions(contractId: UUID) =
            emptyList<com.openbank.pension.application.port.out.FundUnitTransaction>()
    }

    class Contracts : PensionContractRepository {
        val rows = ConcurrentHashMap<UUID, PensionContract>()
        override suspend fun save(contract: PensionContract) = contract.also { rows[it.id] = it }
        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun findByIdempotencyKey(participantPartyId: UUID, idempotencyKey: String) =
            rows.values.firstOrNull {
                it.participantPartyId == participantPartyId && it.idempotencyKey == idempotencyKey
            }
        override suspend fun findByParticipant(participantPartyId: UUID, limit: Int) =
            rows.values.filter { it.participantPartyId == participantPartyId }.take(limit)
        override suspend fun findByStatus(status: ContractStatus?, limit: Int) =
            rows.values.filter { status == null || it.status == status }.take(limit)
    }

    private companion object {
        const val PARTNER_IBAN = "CZ5508000000001234567899"
        const val EDITED_IBAN = "CZ1208000000009876543210"
    }
}
