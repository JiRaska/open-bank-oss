// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.annuity

import com.openbank.pension.application.exit.AnnuityPlacement
import com.openbank.pension.application.exit.AnnuityPlacementPort
import com.openbank.pension.application.exit.AnnuityStepPendingException
import com.openbank.pension.application.exit.ExitForbiddenException
import com.openbank.pension.application.exit.ExitNotFoundException
import com.openbank.pension.application.exit.InstructionStatus
import com.openbank.pension.application.exit.PaymentInstruction
import com.openbank.pension.application.exit.PaymentInstructionRepository
import com.openbank.pension.application.exit.PaymentOrder
import com.openbank.pension.application.exit.PayoutPaymentPort
import com.openbank.pension.application.exit.PayoutRequestRepository
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.domain.annuity.AnnuityCompensation
import com.openbank.pension.domain.annuity.AnnuityOffer
import com.openbank.pension.domain.annuity.AnnuityPackRules
import com.openbank.pension.domain.annuity.AnnuityProvider
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.domain.annuity.AnnuityPurchase
import com.openbank.pension.domain.annuity.AnnuityPurchaseStatus
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.PartnerQuoteFailure
import com.openbank.pension.domain.annuity.RefusedPremiumDestination
import com.openbank.pension.domain.exit.AnnuityPolicy
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/** Persistence the marketplace uses; grouped so the constructor stays inside the fleet bound. */
data class AnnuityStores(
    val purchases: AnnuityPurchaseRepository,
    val providers: AnnuityProviderRepository,
    val payouts: PayoutRequestRepository,
    val instructions: PaymentInstructionRepository,
)

/** The outbound systems the marketplace touches. */
data class AnnuityRails(
    val adapters: AnnuityAdapterCatalog,
    val sca: ScaVerificationPort,
    val payments: PayoutPaymentPort,
    val fund: FundAdministrationPort,
)

/** The participant's wishes for the quote round; null = everything the pack and the partner allow. */
data class AnnuityPreferences(
    val types: Set<AnnuityType>? = null,
    val guaranteeMonths: Int? = null,
    val termMonths: Int? = null,
    val jointLifeBirthDate: LocalDate? = null,
    val survivorShare: BigDecimal? = null,
)

/**
 * The generic annuity lifecycle (#12383): ask every eligible partner IN PARALLEL (bounded by
 * [quoteTimeout], partial results allowed and shown), present the normalised offers in one
 * disclosed, partner-neutral order, take the participant's SCA-signed selection, then — from the
 * payout workflow — apply, send the single premium, and follow the policy to issue or compensate.
 */
@Suppress("TooManyFunctions") // one function per lifecycle step
class AnnuityMarketplaceService(
    private val contracts: PensionContractUseCase,
    private val stores: AnnuityStores,
    private val rails: AnnuityRails,
    private val packs: JurisdictionPackRegistry,
    private val clock: Clock,
    private val quoteTimeout: Duration,
) : AnnuityPlacementPort {

    private val log = Logger.getLogger(AnnuityMarketplaceService::class.java)

    // ---- participant: quote, compare, select ----

    suspend fun requestOffers(
        caller: Caller,
        contractId: UUID,
        payoutId: UUID,
        preferences: AnnuityPreferences,
    ): AnnuityPurchase {
        caller.requireParticipant()
        val contract = contracts.get(caller, contractId)
        val payout = annuityPayout(contractId, payoutId)
        check(payout.status == PayoutStatus.QUOTED) { "offers are requested for a QUOTED annuity payout, was ${payout.status}" }
        val rules = packRules(contract)
        val wanted = (preferences.types ?: rules.permittedTypes).intersect(rules.permittedTypes)
        require(wanted.isNotEmpty()) { "none of the requested annuity types is permitted by the pack" }
        val premium = payout.quote.netAmount
        val currency = payout.quote.currency
        val today = LocalDate.now(clock)
        val jurisdiction = packs.pinnedFor(contract).jurisdiction
        val eligible = stores.providers.list(AnnuityProviderStatus.ACTIVE)
            .filter { it.eligible(jurisdiction, currency, premium, today) }
            .filter { it.terms.supportedTypes.any(wanted::contains) }
        val request = AnnuityQuoteRequest(
            requestId = payoutId.toString(),
            premium = premium,
            currency = currency,
            jurisdiction = jurisdiction,
            birthDate = contract.participantBirthDate,
            startDate = today.plusMonths(1).withDayOfMonth(1),
            types = wanted,
            guaranteeMonths = preferences.guaranteeMonths,
            termMonths = preferences.termMonths,
            jointLifeBirthDate = preferences.jointLifeBirthDate,
            survivorShare = preferences.survivorShare,
        )
        val (offers, failures) = quoteAll(eligible, request, rules)
        val now = clock.instant()
        val existing = stores.purchases.findById(payoutId)
        val purchase = existing?.reoffer(offers, failures, now)
            ?: AnnuityPurchase.offered(payoutId, contractId, contract.participantPartyId, premium, currency, offers, failures, now)
        return stores.purchases.save(purchase)
    }

    suspend fun get(caller: Caller, contractId: UUID, payoutId: UUID): AnnuityPurchase {
        contracts.get(caller, contractId)
        return load(contractId, payoutId)
    }

    /** The SCA challenge must be signed over [AnnuityPurchase.selectionHash] of exactly this offer. */
    suspend fun select(
        caller: Caller,
        contractId: UUID,
        payoutId: UUID,
        partnerId: String,
        offerId: String,
        scaChallengeId: String,
    ): AnnuityPurchase {
        caller.requireParticipant()
        val contract = contracts.get(caller, contractId)
        val payout = annuityPayout(contractId, payoutId)
        check(payout.status == PayoutStatus.QUOTED) { "the payout is ${payout.status}; the selection is closed" }
        val purchase = load(contractId, payoutId)
        if (purchase.status == AnnuityPurchaseStatus.SELECTED &&
            purchase.selectedPartnerId == partnerId && purchase.selectedOfferId == offerId &&
            purchase.scaChallengeId == scaChallengeId
        ) {
            return purchase
        }
        val selected = purchase.select(partnerId, offerId, scaChallengeId, clock.instant())
        if (!rails.sca.verify(contract.participantPartyId, scaChallengeId, requireNotNull(selected.selectionHash))) {
            throw ExitForbiddenException("strong customer authentication failed for this offer")
        }
        return stores.purchases.save(selected)
    }

    /** Cancel an issued policy inside the partner's cooling-off period; SCA over [AnnuityPurchase.cancellationHash]. */
    suspend fun cancel(caller: Caller, contractId: UUID, payoutId: UUID, scaChallengeId: String): AnnuityPurchase {
        caller.requireParticipant()
        val contract = contracts.get(caller, contractId)
        val purchase = load(contractId, payoutId)
        if (purchase.status == AnnuityPurchaseStatus.CANCELLED) return purchase
        // Fail fast on state before spending the participant's challenge.
        purchase.cancelInCoolingOff(LocalDate.now(clock), clock.instant())
        if (!rails.sca.verify(contract.participantPartyId, scaChallengeId, purchase.cancellationHash())) {
            throw ExitForbiddenException("strong customer authentication failed for this cancellation")
        }
        val provider = provider(purchase)
        val adapter = adapterOf(provider)
        val answer = adapter.cancel(
            provider,
            requireNotNull(purchase.applicationRef),
            PartnerCancellationReason.COOLING_OFF,
            key(purchase.id, "cancel"),
        )
        check(answer.state == PartnerPolicyState.CANCELLED) { "the partner did not accept the cancellation: ${answer.reason}" }
        val cancelled = stores.purchases.save(purchase.cancelInCoolingOff(LocalDate.now(clock), clock.instant()))
        return if (answer.refundRef != null) refundCancelledToClient(cancelled, contract) else cancelled
    }

    // ---- operator ----

    suspend fun list(status: AnnuityPurchaseStatus?, limit: Int): List<AnnuityPurchase> =
        stores.purchases.list(status, limit.coerceIn(1, MAX_LIST))

    /**
     * Status sync (operator, or a retry): asks the partner where an in-flight purchase or a
     * cancelled policy's refund stands and applies the answer. The payout workflow, if still
     * running, picks the result up on its next attempt.
     */
    suspend fun sync(purchaseId: UUID): AnnuityPurchase {
        val purchase = stores.purchases.findById(purchaseId) ?: throw ExitNotFoundException("annuity purchase $purchaseId not found")
        val contract = contracts.get(Caller.STAFF, purchase.contractId)
        return when (purchase.status) {
            AnnuityPurchaseStatus.PREMIUM_SENT -> followPolicy(purchase, provider(purchase))
            AnnuityPurchaseStatus.CANCELLED ->
                if (purchase.compensation == AnnuityCompensation.NONE) {
                    val status = adapterOf(provider(purchase)).status(provider(purchase), requireNotNull(purchase.applicationRef))
                    if (status.refundRef != null) refundCancelledToClient(purchase, contract) else purchase
                } else {
                    purchase
                }
            else -> purchase
        }
    }

    // ---- exit slice: binding selection and placement ----

    override suspend fun requireBindingSelection(payoutId: UUID, premium: BigDecimal) {
        val purchase = stores.purchases.findById(payoutId)
            ?: throw IllegalStateException("request annuity offers and select one before confirming")
        val offer = purchase.requireBindingSelection(premium, clock.instant())
        val provider = stores.providers.find(offer.partnerId)
        check(provider?.status == AnnuityProviderStatus.ACTIVE) { "the selected partner is no longer active" }
    }

    override suspend fun place(payout: PayoutRequest, contract: PensionContract): AnnuityPlacement {
        var purchase = requireNotNull(stores.purchases.findById(payout.id)) { "payout ${payout.id} has no annuity selection" }
        val provider = stores.providers.find(requireNotNull(purchase.selectedPartnerId))
        if (purchase.status == AnnuityPurchaseStatus.SELECTED) purchase = apply(purchase, provider, contract)
        if (purchase.status == AnnuityPurchaseStatus.APPLIED) purchase = sendPremium(purchase, requireNotNull(provider))
        if (purchase.status == AnnuityPurchaseStatus.PREMIUM_SENT) purchase = followPolicy(purchase, requireNotNull(provider))
        return when (purchase.status) {
            AnnuityPurchaseStatus.ACTIVE -> AnnuityPlacement.Issued(
                AnnuityPolicy(
                    policyRef = requireNotNull(purchase.policyRef),
                    insurerRef = requireNotNull(purchase.selectedPartnerId),
                    monthlyAmount = requireNotNull(purchase.policyMonthlyAmount),
                ),
            )
            AnnuityPurchaseStatus.FAILED -> compensate(purchase, payout, contract)
            else -> throw AnnuityStepPendingException("annuity purchase ${purchase.id} is ${purchase.status}")
        }
    }

    // ---- steps ----

    /** Application first: a partner that refuses up front never receives a premium. */
    private suspend fun apply(purchase: AnnuityPurchase, provider: AnnuityProvider?, contract: PensionContract): AnnuityPurchase {
        val adapter = provider?.takeIf { it.status == AnnuityProviderStatus.ACTIVE }?.let { rails.adapters.adapterFor(it.terms.adapter) }
        if (provider == null || adapter == null) {
            return stores.purchases.save(purchase.fail("the selected partner is not available", clock.instant()))
        }
        val offer = requireNotNull(purchase.selectedOffer)
        val answer = adapter.purchase(
            provider,
            AnnuityApplication(
                requestId = purchase.id.toString(),
                offerId = offer.offerId,
                premium = purchase.premium,
                currency = purchase.currency,
                holderReference = contract.participantPartyId.toString(),
                birthDate = contract.participantBirthDate,
                premiumReference = premiumReference(purchase),
                idempotencyKey = key(purchase.id, "apply"),
            ),
        )
        val next = when (answer.state) {
            PartnerPolicyState.REFUSED, PartnerPolicyState.CANCELLED ->
                purchase.fail("refused by ${provider.partnerId}: ${answer.reason ?: "no reason given"}", clock.instant())
            else -> purchase.markApplied(answer.applicationRef, clock.instant())
        }
        return stores.purchases.save(next)
    }

    /**
     * The single premium leaves through the payout payment rail, recorded once per key. A
     * DEFINITIVE rejection by the rail (a domain refusal, not a transport error) cancels the
     * application at the partner: premium not sent → no policy.
     */
    private suspend fun sendPremium(purchase: AnnuityPurchase, provider: AnnuityProvider): AnnuityPurchase {
        val ref = try {
            pay(
                key(purchase.id, "premium"),
                purchase.contractId,
                "ANNUITY_PREMIUM",
                provider.terms.legalName,
                provider.terms.premiumIban,
                purchase.premium,
                purchase.currency,
                premiumReference(purchase),
            )
        } catch (e: IllegalStateException) {
            return premiumNotSent(purchase, provider, e)
        } catch (e: IllegalArgumentException) {
            return premiumNotSent(purchase, provider, e)
        }
        return stores.purchases.save(purchase.markPremiumSent(ref, clock.instant()))
    }

    private suspend fun premiumNotSent(purchase: AnnuityPurchase, provider: AnnuityProvider, cause: Exception): AnnuityPurchase {
        log.warnf("annuity %s: premium rejected by the payment rail (%s); cancelling the application", purchase.id, cause.message)
        adapterOf(provider).cancel(
            provider,
            requireNotNull(purchase.applicationRef),
            PartnerCancellationReason.PREMIUM_NOT_SENT,
            key(purchase.id, "cancel-unpaid"),
        )
        return stores.purchases.save(purchase.fail("premium not sent: ${cause.message}", clock.instant()))
    }

    /** Policy issued → ACTIVE. Refused → FAILED once the partner has RETURNED the premium; until then, pending. */
    private suspend fun followPolicy(purchase: AnnuityPurchase, provider: AnnuityProvider): AnnuityPurchase {
        val answer = adapterOf(provider).status(provider, requireNotNull(purchase.applicationRef))
        val now = clock.instant()
        return when (answer.state) {
            PartnerPolicyState.ACTIVE -> stores.purchases.save(
                purchase.markActive(
                    requireNotNull(answer.policyRef) { "an ACTIVE policy states its policyRef" },
                    answer.monthlyAmount ?: requireNotNull(purchase.selectedOffer).monthlyAmount,
                    answer.issuedOn ?: LocalDate.now(clock),
                    provider.terms.coolingOffDays,
                    now,
                ),
            )
            PartnerPolicyState.REFUSED, PartnerPolicyState.CANCELLED ->
                if (answer.refundRef == null) {
                    throw AnnuityStepPendingException("policy refused by ${provider.partnerId}; premium refund pending")
                } else {
                    stores.purchases.save(purchase.fail("refused by ${provider.partnerId}: ${answer.reason}", now))
                }
            else -> purchase
        }
    }

    /** The money of a failed purchase goes where the PACK says: back into the contract, or to the client. */
    private suspend fun compensate(purchase: AnnuityPurchase, payout: PayoutRequest, contract: PensionContract): AnnuityPlacement {
        when (purchase.compensation) {
            AnnuityCompensation.RETURNED_TO_CLIENT -> return AnnuityPlacement.ReturnedToClient(requireNotNull(purchase.compensationRef))
            AnnuityCompensation.RETURNED_TO_CONTRACT -> return AnnuityPlacement.ReturnedToContract
            AnnuityCompensation.NONE -> Unit
        }
        val destination = packs.pinnedFor(contract).exit?.annuity?.refusedPremiumDestination
            ?: RefusedPremiumDestination.CONTRACT
        return when (destination) {
            RefusedPremiumDestination.CLIENT -> {
                val ref = pay(
                    key(purchase.id, "refund-client"),
                    purchase.contractId,
                    "ANNUITY_REFUND",
                    contract.participantPartyId.toString(),
                    requireNotNull(payout.payoutIban) { "the payout has no signed account" },
                    purchase.premium,
                    purchase.currency,
                    "PENSION ANNUITY REFUND ${purchase.contractId}",
                )
                stores.purchases.save(purchase.markCompensated(AnnuityCompensation.RETURNED_TO_CLIENT, ref, clock.instant()))
                AnnuityPlacement.ReturnedToClient(ref)
            }
            RefusedPremiumDestination.CONTRACT -> {
                val order = rails.fund.subscribe(purchase.contractId, purchase.premium, purchase.currency, key(purchase.id, "resubscribe"))
                stores.purchases.save(purchase.markCompensated(AnnuityCompensation.RETURNED_TO_CONTRACT, order, clock.instant()))
                AnnuityPlacement.ReturnedToContract
            }
        }
    }

    /** A cooling-off refund always goes to the client: the contract is already paid out. */
    private suspend fun refundCancelledToClient(purchase: AnnuityPurchase, contract: PensionContract): AnnuityPurchase {
        val payout = requireNotNull(stores.payouts.findById(purchase.id)) { "payout ${purchase.id} vanished" }
        val ref = pay(
            key(purchase.id, "refund-cooling-off"),
            purchase.contractId,
            "ANNUITY_COOLING_OFF_REFUND",
            contract.participantPartyId.toString(),
            requireNotNull(payout.payoutIban),
            purchase.premium,
            purchase.currency,
            "PENSION ANNUITY REFUND ${purchase.contractId}",
        )
        return stores.purchases.save(purchase.markCompensated(AnnuityCompensation.RETURNED_TO_CLIENT, ref, clock.instant()))
    }

    // ---- quoting ----

    private suspend fun quoteAll(
        providers: List<AnnuityProvider>,
        request: AnnuityQuoteRequest,
        rules: AnnuityPackRules,
    ): Pair<List<AnnuityOffer>, List<PartnerQuoteFailure>> = coroutineScope {
        val answers = providers.map { provider ->
            async { provider to quoteOne(provider, request.copy(types = request.types.intersect(provider.terms.supportedTypes))) }
        }.awaitAll()
        val minValid = clock.instant().plus(Duration.ofHours(rules.quoteValidityMinHours.toLong()))
        val offers = mutableListOf<AnnuityOffer>()
        val failures = mutableListOf<PartnerQuoteFailure>()
        for ((provider, result) in answers) {
            result.fold(
                onSuccess = { received ->
                    val (valid, invalid) = received.partition { offerFits(it, provider, request, minValid) }
                    offers += valid.map { it.copy(partnerName = provider.terms.legalName) }
                    if (invalid.isNotEmpty()) failures += PartnerQuoteFailure(provider.partnerId, "${invalid.size} offer(s) did not match the request")
                    if (received.isEmpty()) failures += PartnerQuoteFailure(provider.partnerId, "no offer")
                },
                onFailure = { failures += PartnerQuoteFailure(provider.partnerId, it.message ?: it.javaClass.simpleName) },
            )
        }
        present(offers) to failures.sortedBy { it.partnerId }
    }

    private suspend fun quoteOne(provider: AnnuityProvider, request: AnnuityQuoteRequest): Result<List<AnnuityOffer>> {
        val adapter = rails.adapters.adapterFor(provider.terms.adapter)
            ?: return Result.failure(IllegalStateException("adapter '${provider.terms.adapter}' is not available in this build"))
        return try {
            withTimeoutOrNull(quoteTimeout.toMillis()) { Result.success(adapter.quote(provider, request)) }
                ?: Result.failure(IllegalStateException("timed out after ${quoteTimeout.toMillis()} ms"))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // One partner's failure is reported next to the others' offers, never fatal to the round.
            log.warnf("annuity quote from %s failed: %s", provider.partnerId, e.toString())
            Result.failure(IllegalStateException("partner unavailable"))
        }
    }

    /**
     * NORMALISATION is enforced here, not trusted from the adapter: an offer must answer THIS
     * request (partner, premium, currency, a requested type) and stay valid long enough to choose.
     */
    private fun offerFits(offer: AnnuityOffer, provider: AnnuityProvider, request: AnnuityQuoteRequest, minValid: java.time.Instant) =
        offer.partnerId == provider.partnerId &&
            offer.premium.compareTo(request.premium) == 0 &&
            offer.currency == request.currency &&
            offer.type in request.types &&
            !offer.validUntil.isBefore(minValid)

    // ---- helpers ----

    private suspend fun annuityPayout(contractId: UUID, payoutId: UUID): PayoutRequest {
        val payout = stores.payouts.findById(payoutId)?.takeIf { it.contractId == contractId }
            ?: throw ExitNotFoundException("payout $payoutId not found")
        require(payout.form == PayoutForm.ANNUITY) { "annuity offers apply to an ANNUITY payout, was ${payout.form}" }
        return payout
    }

    private fun packRules(contract: PensionContract): AnnuityPackRules =
        checkNotNull(packs.pinnedFor(contract).exit?.annuity) { "the pack states no annuity rules" }

    private suspend fun load(contractId: UUID, payoutId: UUID): AnnuityPurchase =
        stores.purchases.findById(payoutId)?.takeIf { it.contractId == contractId }
            ?: throw ExitNotFoundException("no annuity offers for payout $payoutId")

    private suspend fun provider(purchase: AnnuityPurchase): AnnuityProvider =
        requireNotNull(stores.providers.find(requireNotNull(purchase.selectedPartnerId))) {
            "partner ${purchase.selectedPartnerId} vanished from the registry"
        }

    private fun adapterOf(provider: AnnuityProvider): AnnuityProviderAdapter =
        checkNotNull(rails.adapters.adapterFor(provider.terms.adapter)) {
            "adapter '${provider.terms.adapter}' is not available in this build"
        }

    @Suppress("LongParameterList")
    private suspend fun pay(
        key: String,
        contractId: UUID,
        purpose: String,
        creditor: String,
        iban: String,
        amount: BigDecimal,
        currency: String,
        reference: String,
    ): String {
        val stored = stores.instructions.recordIfAbsent(
            PaymentInstruction(key, contractId, purpose, amount, currency, iban, InstructionStatus.PENDING),
        )
        stored.paymentRef?.let { return it }
        check(stored.amount.compareTo(amount) == 0) { "instruction $key exists with a different amount" }
        val ref = rails.payments.pay(PaymentOrder(key, contractId, creditor, iban, amount, currency, reference))
        stores.instructions.markSent(key, ref)
        return ref
    }

    companion object {
        const val MAX_LIST = 200

        fun key(purchaseId: UUID, step: String) = "pension-annuity-$purchaseId-$step"

        fun premiumReference(purchase: AnnuityPurchase) = "PENSION ANNUITY ${purchase.id}"

        /**
         * The ONE presentation order, disclosed to the participant (no steering): grouped by
         * annuity type, then the highest monthly amount first, then partner id as a neutral
         * tie-break. No offer is marked recommended, and no partner attribute other than the
         * offer's own figures influences the order.
         */
        fun present(offers: List<AnnuityOffer>): List<AnnuityOffer> =
            offers.sortedWith(
                compareBy<AnnuityOffer> { it.type.ordinal }
                    .thenByDescending { it.monthlyAmount }
                    .thenBy { it.partnerId }
                    .thenBy { it.offerId },
            )

        const val PRESENTATION_ORDER = "annuityType, then monthlyAmount descending, then partnerId"
    }
}
