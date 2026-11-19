// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/**
 * ADR-0383 D6 — what a customer MCP client may do with a payment: PROPOSE it, never more.
 *
 * A proposal is an immutable record of exactly one payment instruction (payee, amount, currency,
 * reference, debtor account) that a connected MCP client asked the bank to put in front of the
 * customer. It moves no money. It is executed only after the customer approves it in the bank app
 * with full SCA whose dynamic linking covers the amount, the payee AND [payloadSha256], and only
 * through the existing customer payment initiation path, by the customer's own session.
 */
enum class PaymentProposalOperationType {
    /** A CZK credit transfer to a Czech account (domestic-payment rail). */
    DOMESTIC_PAYMENT,

    /** A EUR SEPA credit transfer (sepa-payment rail). */
    SEPA_PAYMENT,
}

/**
 * The lifecycle. Every transition is one-way: [PENDING] is the only state anything can leave, and
 * [APPROVED] can only become [EXECUTED] or [EXECUTION_FAILED]. Nothing ever returns to [PENDING],
 * which is what makes a proposal one-shot.
 */
enum class PaymentProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    EXECUTED,
    EXECUTION_FAILED,
    ;

    val isTerminal: Boolean get() = this != PENDING && this != APPROVED
}

/** The payment the proposal describes. Every field is fixed at creation (V35 trigger pins it). */
data class PaymentProposalPayload(
    val operationType: PaymentProposalOperationType,
    val debtorAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val reference: String? = null,
) {
    init {
        require(creditorIban.matches(IBAN_SHAPE)) { "creditorIban must be an uppercase IBAN without spaces" }
        require(creditorName.isNotBlank() && creditorName.length <= MAX_NAME) {
            "creditorName must be 1..$MAX_NAME characters"
        }
        require(reference == null || (reference.isNotBlank() && reference.length <= MAX_REFERENCE)) {
            "reference must be 1..$MAX_REFERENCE characters when present"
        }
        requireSafeText(creditorName, "creditorName")
        reference?.let { requireSafeText(it, "reference") }
        require(amount.signum() > 0) { "amount must be positive" }
        require(amount.scale() <= 2) { "amount must have at most two fractional digits" }
        require(amount <= MAX_AMOUNT) { "amount exceeds the proposal ceiling" }
        when (operationType) {
            PaymentProposalOperationType.DOMESTIC_PAYMENT -> {
                require(currency == "CZK") { "a domestic payment proposal must be in CZK" }
                require(creditorIban.startsWith("CZ")) { "a domestic payment proposal needs a Czech creditor IBAN" }
            }
            PaymentProposalOperationType.SEPA_PAYMENT ->
                require(currency == "EUR") { "a SEPA payment proposal must be in EUR" }
        }
    }

    /** Two decimals, no exponent: the form both the hash and the SCA dynamic linking use. */
    val canonicalAmount: String get() = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    /** The same payment, for ADR-0383 D6 deduplication (same payee, amount and currency). */
    fun sameInstructionAs(other: PaymentProposalPayload): Boolean = operationType == other.operationType &&
        debtorAccountId == other.debtorAccountId &&
        creditorIban == other.creditorIban &&
        amount.compareTo(other.amount) == 0 &&
        currency == other.currency

    companion object {
        const val MAX_NAME = 70
        const val MAX_REFERENCE = 140
        val MAX_AMOUNT: BigDecimal = BigDecimal("1000000.00")
        private val IBAN_SHAPE = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")

        /**
         * ADR-0383 D7: model-composed free text is length-capped and may carry no control
         * characters and no link/image markup, because the app renders it next to a payment the
         * customer is about to sign. Refused, not stripped: a silently rewritten reference is a
         * different payment from the one the model described.
         */
        // Unicode categories, not ASCII \p{Cntrl}: Cc (controls), Cf (format: bidi overrides such as
        // U+202E, zero-width U+200B/U+200D, U+FEFF) and Zl/Zp (U+2028/U+2029) can make a payee name
        // render as a different one on the approval screen.
        private val UNSAFE = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}<>\\[\\]`]|://")

        /** NFC so visually identical names compare and hash identically; callers normalise first. */
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

        private fun requireSafeText(value: String, field: String) {
            require(Normalizer.isNormalized(value, Normalizer.Form.NFC)) { "$field must be NFC-normalised" }
            require(!UNSAFE.containsMatchIn(value)) { "$field contains control, format or markup characters" }
        }
    }
}

@Suppress("TooManyFunctions") // the lifecycle transitions are the aggregate's whole API
data class PaymentProposal(
    val id: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val payload: PaymentProposalPayload,
    val payloadSha256: String,
    val status: PaymentProposalStatus,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val revision: Long = 0,
    val decidedAt: OffsetDateTime? = null,
    val executedPaymentId: UUID? = null,
) {
    init {
        require(expiresAt.isAfter(createdAt)) { "proposal expiry must be after creation" }
        require(payloadSha256 == canonicalSha256(id, partyId, payload)) {
            "payloadSha256 does not describe this proposal's payload"
        }
    }

    /** The status a reader must see: a PENDING proposal past its expiry is EXPIRED, persisted or not. */
    fun effectiveStatus(now: OffsetDateTime): PaymentProposalStatus = if (status == PaymentProposalStatus.PENDING &&
        !now.isBefore(expiresAt)
    ) {
        PaymentProposalStatus.EXPIRED
    } else {
        status
    }

    fun isOpen(now: OffsetDateTime): Boolean = effectiveStatus(now) == PaymentProposalStatus.PENDING

    /**
     * The customer's SCA-backed approval. The caller re-supplies the hash the app rendered and the
     * device signed; it must equal the stored one, so an approval can only ever release the exact
     * bytes the customer saw. Refused unless still PENDING and unexpired — a second approval, or a
     * replay of the first, finds APPROVED (or later) and is refused.
     */
    fun approve(suppliedSha256: String, now: OffsetDateTime): PaymentProposal {
        requireHash(suppliedSha256)
        requireOpen(now)
        return transition(PaymentProposalStatus.APPROVED, now)
    }

    fun reject(now: OffsetDateTime): PaymentProposal {
        requireOpen(now)
        return transition(PaymentProposalStatus.REJECTED, now)
    }

    fun expire(now: OffsetDateTime): PaymentProposal {
        if (status != PaymentProposalStatus.PENDING || now.isBefore(expiresAt)) {
            throw PaymentProposalStateException("proposal $id is not due to expire")
        }
        return transition(PaymentProposalStatus.EXPIRED, now)
    }

    /** Execution is recorded against the SAME hash: the instruction that ran is the one approved. */
    fun recordExecuted(suppliedSha256: String, paymentId: UUID, now: OffsetDateTime): PaymentProposal {
        requireHash(suppliedSha256)
        requireStatus(PaymentProposalStatus.APPROVED)
        return transition(PaymentProposalStatus.EXECUTED, now).copy(executedPaymentId = paymentId)
    }

    fun recordExecutionFailed(suppliedSha256: String, now: OffsetDateTime): PaymentProposal {
        requireHash(suppliedSha256)
        requireStatus(PaymentProposalStatus.APPROVED)
        return transition(PaymentProposalStatus.EXECUTION_FAILED, now)
    }

    private fun requireHash(suppliedSha256: String) {
        if (!MessageDigest.isEqual(
                suppliedSha256.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII),
                payloadSha256.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw PaymentProposalHashMismatchException(id)
        }
    }

    private fun requireOpen(now: OffsetDateTime) {
        val effective = effectiveStatus(now)
        if (effective != PaymentProposalStatus.PENDING) {
            throw PaymentProposalStateException("proposal $id is $effective, not PENDING")
        }
    }

    private fun requireStatus(expected: PaymentProposalStatus) {
        if (status != expected) throw PaymentProposalStateException("proposal $id is $status, not $expected")
    }

    private fun transition(next: PaymentProposalStatus, now: OffsetDateTime) =
        copy(status = next, decidedAt = decidedAt ?: now, revision = Math.addExact(revision, 1))

    companion object {
        const val APPROVAL_REQUEST_PREFIX = "mcp-proposal:"
        private const val DOMAIN = "openbank.payment-proposal.v1"

        /**
         * The canonical bytes a proposal is identified by. One field per line in a fixed order,
         * prefixed by a domain-separation tag; [PaymentProposalPayload] refuses control characters,
         * so no value can forge a line break. The proposal id and party are inside the hash, so a
         * signature over one proposal can never match another, even for an identical payment.
         *
         * customer-edge recomputes this exact function before it spends a challenge; the test
         * vector in PaymentProposalTest is shared with it (CustomerProposalHashTest) so the two
         * implementations cannot drift apart unnoticed.
         */
        fun canonicalForm(id: UUID, partyId: UUID, payload: PaymentProposalPayload): String = buildString {
            append(DOMAIN).append('\n')
            append("proposalId=").append(id).append('\n')
            append("partyId=").append(partyId).append('\n')
            append("operationType=").append(payload.operationType.name).append('\n')
            append("debtorAccountId=").append(payload.debtorAccountId).append('\n')
            append("creditorIban=").append(payload.creditorIban).append('\n')
            append("creditorName=").append(payload.creditorName).append('\n')
            append("amount=").append(payload.canonicalAmount).append('\n')
            append("currency=").append(payload.currency).append('\n')
            append("reference=").append(payload.reference.orEmpty()).append('\n')
        }

        fun canonicalSha256(id: UUID, partyId: UUID, payload: PaymentProposalPayload): String =
            MessageDigest.getInstance("SHA-256")
                .digest(canonicalForm(id, partyId, payload).toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun approvalRequestId(id: UUID): String = APPROVAL_REQUEST_PREFIX + id
    }
}

class PaymentProposalStateException(message: String) : RuntimeException(message)

class PaymentProposalHashMismatchException(id: UUID) : RuntimeException("payloadSha256 does not match proposal $id")

class PaymentProposalNotFoundException : RuntimeException("payment proposal not found")

class PaymentProposalLimitException(message: String) : RuntimeException(message)

/** No ACTIVE MCP_CLIENT grant for (party, client, key) carries propose-payment over this account. */
class PaymentProposalNotPermittedException(message: String) : RuntimeException(message)
