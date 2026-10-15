// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.pension.application.exit.PaymentOrder
import com.openbank.pension.application.exit.PayoutPaymentPort
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.domain.contribution.MandateKind
import com.openbank.pension.domain.model.ContributionFrequency
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/** A payment rail refused, or this deployment lacks the configuration to address it. Never papered over. */
class PaymentRailRefusedException(message: String) : IllegalStateException(message)

private fun refuse(message: String): Nothing = throw PaymentRailRefusedException(message)

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** The provider's own collection account and SEPA creditor identity (config, never caller input). */
data class CollectionAccount(val iban: String, val name: String, val creditorIdentifier: String?)

/** The provider's own payout account at this bank (config, never caller input). */
data class PayoutAccount(val accountId: UUID, val accountNumber: String, val bankCode: String, val name: String)

/** The routes [MandateOrders] calls, without JAX-RS annotations, so a unit test can fake them. */
interface MandateRails {
    suspend fun createStandingOrder(request: CreateStandingOrderDto): StandingOrderDto
    suspend fun cancelStandingOrder(id: UUID)
    suspend fun registerSddMandate(request: RegisterSddMandateDto): SddMandateDto
    suspend fun cancelSddMandate(id: UUID)
}

/**
 * The [PaymentMandatePort] decisions (#12378), free of CDI.
 *
 * - **Standing order** (standing-order-service): the participant's account pays the provider's
 *   collection IBAN at the contract's contribution frequency, quoting the contract's payment
 *   reference as remittance info so [com.openbank.pension.application.usecase.ContributionService.receive]
 *   matches it. EUR executes as SEPA_CREDIT; any other currency as DOMESTIC (standing-order-service
 *   refuses a non-EUR SEPA_CREDIT). The idempotency key is a hash of (contract, kind, first
 *   collection, amount): a retried set-up returns the order already created.
 * - **SEPA direct debit** (sdd-service): registers the DEBTOR-side mandate on the participant's
 *   account, so the debtor bank authorises the provider's collections. Idempotent on
 *   (creditor identifier, UMR) at the provider; the UMR is derived from the contract reference.
 *   Initiating the collections themselves is a creditor-side rail no service offers yet (#12387)
 *   — the mandate is what this port can make true today.
 *
 * Every missing input is a refusal before any call: no debtor account id, no debtor name for a
 * direct debit, or no configured collection account means nothing is sent.
 */
class MandateOrders(
    private val rails: MandateRails,
    private val collection: () -> CollectionAccount?,
    private val clock: Clock,
) : PaymentMandatePort {

    override suspend fun setUp(request: MandateRequest): String {
        val target =
            collection() ?: refuse("no collection account is configured (openbank.pension.payments.collection.*)")
        val accountId = request.debtorAccountId ?: refuse("debtorAccountId is required to set up a ${request.kind}")
        return when (request.kind) {
            MandateKind.STANDING_ORDER -> rails.createStandingOrder(standingOrder(request, accountId, target)).id
            MandateKind.DIRECT_DEBIT -> {
                val cid = target.creditorIdentifier
                    ?: refuse(
                        "no SEPA creditor identifier is configured (openbank.pension.payments.collection.creditor-identifier)",
                    )
                val debtorName = request.debtorName ?: refuse("debtorName is required for a SEPA direct-debit mandate")
                rails.registerSddMandate(
                    RegisterSddMandateDto(
                        partyId = request.participantPartyId,
                        accountId = accountId,
                        debtorIban = request.debtorIban.replace(" ", "").uppercase(),
                        creditorIdentifier = cid,
                        umr = umr(request.reference),
                        scheme = "CORE",
                        sequenceType = "RCUR",
                        creditorName = target.name,
                        debtorName = debtorName,
                        signatureDate = LocalDate.now(clock),
                    ),
                ).id
            }
        }.toString()
    }

    override suspend fun cancel(kind: MandateKind, externalId: String) {
        val id = runCatching {
            UUID.fromString(externalId)
        }.getOrElse { refuse("mandate id $externalId is not a rail id") }
        when (kind) {
            MandateKind.STANDING_ORDER -> rails.cancelStandingOrder(id)
            MandateKind.DIRECT_DEBIT -> rails.cancelSddMandate(id)
        }
    }

    private fun standingOrder(
        request: MandateRequest,
        accountId: UUID,
        target: CollectionAccount,
    ): CreateStandingOrderDto {
        val minor = request.amount.movePointRight(MINOR_DIGITS)
        if (minor.stripTrailingZeros().scale() >
            0
        ) {
            refuse("amount ${request.amount} has more than $MINOR_DIGITS decimals")
        }
        return CreateStandingOrderDto(
            idempotencyKey = "pension-so-" + sha256(
                "${request.contractId}|${request.kind}|${request.firstCollection}|${request.amount.toPlainString()}",
            ),
            partyId = request.participantPartyId,
            debitAccountId = accountId,
            debtorIban = request.debtorIban.replace(" ", "").uppercase(),
            debtorName = request.debtorName,
            creditorIban = target.iban,
            creditorName = target.name,
            creditorBic = null,
            amountMinorUnits = minor.longValueExact(),
            currency = request.currency,
            frequency = request.frequency.toRail(),
            paymentType = if (request.currency.equals("EUR", ignoreCase = true)) "SEPA_CREDIT" else "DOMESTIC",
            remittanceInfo = request.reference,
            startDate = request.firstCollection,
            endDate = null,
        )
    }

    private fun ContributionFrequency.toRail() = when (this) {
        ContributionFrequency.MONTHLY -> "MONTHLY"
        ContributionFrequency.QUARTERLY -> "QUARTERLY"
        ContributionFrequency.ANNUALLY -> "ANNUALLY"
    }

    companion object {
        private const val MINOR_DIGITS = 2

        /** SEPA UMR: at most 35 characters. Stable per contract reference, so a retry is the same mandate. */
        private const val UMR_MAX = 35

        fun umr(reference: String): String {
            require(reference.isNotBlank()) { "the contract payment reference is required for a UMR" }
            return "PENSION-$reference".take(UMR_MAX)
        }
    }
}

/** The one route [PayoutOrders] calls, without JAX-RS annotations. */
fun interface PayoutRail {
    suspend fun create(idempotencyKey: String, request: CreateDomesticPaymentDto): DomesticPaymentDto
}

/**
 * The [PayoutPaymentPort] decisions (#12378), free of CDI: a CZK credit transfer from the
 * provider's configured payout account through domestic-payment.
 *
 * The `Idempotency-Key` is the payout step's own key ([PaymentOrder.idempotencyKey], derived from
 * the aggregate and the step, never random), so a retried Temporal activity re-sends the SAME
 * instruction and domestic-payment returns the payment it already created (its `X-Idempotency-
 * Replayed`). The returned reference is domestic-payment's payment id — the key its status events
 * carry, which is how the settlement comes back ([com.openbank.pension.application.usecase.PayoutSettlementService]).
 *
 * The domestic rail is CZK-only and addresses a Czech account; the creditor IBAN must therefore
 * be a CZ IBAN whose prefix and base pass the national mod-11 check. Anything else is refused
 * before a call — a payout is never sent to an account the rail would reject after accepting.
 */
class PayoutOrders(private val rail: PayoutRail, private val payoutAccount: () -> PayoutAccount?) : PayoutPaymentPort {

    override suspend fun pay(order: PaymentOrder): String {
        val from = payoutAccount() ?: refuse("no payout account is configured (openbank.pension.payments.payout.*)")
        if (!order.currency.equals(CZK, ignoreCase = true)) {
            refuse("domestic-payment pays CZK only; payout ${order.idempotencyKey} is in ${order.currency}")
        }
        val (accountNumber, bankCode) = czechAccount(order.creditorIban)
        val payment = rail.create(
            order.idempotencyKey,
            CreateDomesticPaymentDto(
                debtorAccountId = from.accountId,
                debtorAccountNumber = from.accountNumber,
                debtorBankCode = from.bankCode,
                debtorName = from.name,
                creditorAccountNumber = accountNumber,
                creditorBankCode = bankCode,
                creditorName = order.creditorName,
                amount = order.amount,
                currency = CZK,
                priority = "STANDARD",
                messageForPayee = order.reference.take(MESSAGE_MAX),
                endToEndId = "PENSION-" + sha256(order.idempotencyKey).take(END_TO_END_HASH),
            ),
        )
        return payment.id.toString()
    }

    companion object {
        private const val CZK = "CZK"
        private const val MESSAGE_MAX = 140
        private const val END_TO_END_HASH = 24
        private const val CZ_IBAN_LENGTH = 24
        private const val BANK_CODE_START = 4
        private const val BANK_CODE_END = 8
        private const val PREFIX_END = 14

        /** CZkk BBBB PPPPPP NNNNNNNNNN → ("prefix-base" | "base", bank code). */
        fun czechAccount(rawIban: String): Pair<String, String> {
            val iban = rawIban.replace(" ", "").uppercase()
            if (iban.length != CZ_IBAN_LENGTH || !iban.startsWith("CZ") || !iban.drop(2).all { it.isDigit() }) {
                refuse("payout account is not a Czech IBAN; domestic-payment cannot address it")
            }
            val bankCode = iban.substring(BANK_CODE_START, BANK_CODE_END)
            val prefix = iban.substring(BANK_CODE_END, PREFIX_END).trimStart('0')
            val base = iban.substring(PREFIX_END).trimStart('0')
            if (!CzechAccountNumber.isValid(prefix, base)) refuse("payout account fails the Czech mod-11 account check")
            return (if (prefix.isEmpty()) base else "$prefix-$base") to bankCode
        }
    }
}

private fun Optional<String>.value(): String? = orElse(null)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The REAL [PaymentMandatePort] (#12378): standing-order-service and sdd-service. The only bean in
 * a prod build; `StubPaymentMandateAdapter` exists only in `%dev`/`%test`.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class PaymentMandateRestAdapter : PaymentMandatePort {

    @Inject
    @RestClient
    lateinit var standingOrders: StandingOrderRestClient

    @Inject
    @RestClient
    lateinit var sdd: SddMandateRestClient

    @Inject
    lateinit var clock: Clock

    @ConfigProperty(name = "openbank.pension.payments.collection.iban")
    lateinit var iban: Optional<String>

    @ConfigProperty(name = "openbank.pension.payments.collection.name")
    lateinit var name: Optional<String>

    @ConfigProperty(name = "openbank.pension.payments.collection.creditor-identifier")
    lateinit var creditorIdentifier: Optional<String>

    private val logic by lazy {
        MandateOrders(
            object : MandateRails {
                override suspend fun createStandingOrder(request: CreateStandingOrderDto) =
                    standingOrders.create(request)
                override suspend fun cancelStandingOrder(id: UUID) = standingOrders.cancel(id)
                override suspend fun registerSddMandate(request: RegisterSddMandateDto) = sdd.register(request)
                override suspend fun cancelSddMandate(id: UUID) {
                    sdd.cancel(id)
                }
            },
            {
                val i = iban.value()
                val n = name.value()
                if (i == null || n == null) null else CollectionAccount(i, n, creditorIdentifier.value())
            },
            clock,
        )
    }

    override suspend fun setUp(request: MandateRequest): String = logic.setUp(request)

    override suspend fun cancel(kind: MandateKind, externalId: String) = logic.cancel(kind, externalId)
}

/**
 * The REAL [PayoutPaymentPort] (#12378): domestic-payment. The only bean in a prod build;
 * `StubPayoutPaymentAdapter` exists only in `%dev`/`%test`.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class DomesticPayoutPaymentAdapter : PayoutPaymentPort {

    @Inject
    @RestClient
    lateinit var client: DomesticPaymentRestClient

    @ConfigProperty(name = "openbank.pension.payments.payout.account-id")
    lateinit var accountId: Optional<String>

    @ConfigProperty(name = "openbank.pension.payments.payout.account-number")
    lateinit var accountNumber: Optional<String>

    @ConfigProperty(name = "openbank.pension.payments.payout.bank-code")
    lateinit var bankCode: Optional<String>

    @ConfigProperty(name = "openbank.pension.payments.payout.name")
    lateinit var name: Optional<String>

    private val logic by lazy {
        PayoutOrders(
            { key, request -> client.create(key, request) },
            {
                val id = accountId.value()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val number = accountNumber.value()
                val code = bankCode.value()
                val holder = name.value()
                if (listOf(id, number, code, holder).any { it == null }) {
                    null
                } else {
                    PayoutAccount(id!!, number!!, code!!, holder!!)
                }
            },
        )
    }

    override suspend fun pay(order: PaymentOrder): String = logic.pay(order)
}
