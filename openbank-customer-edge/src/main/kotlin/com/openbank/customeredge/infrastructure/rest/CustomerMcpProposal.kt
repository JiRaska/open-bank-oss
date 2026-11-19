// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0383 D6 — a payment proposal as delegation-service returned it, re-verified HERE before any
 * SCA challenge is spent. The edge never trusts the store's hash alone: it recomputes the
 * canonical SHA-256 from the returned instruction (the same function as delegation-service's
 * `PaymentProposal.canonicalSha256`; the shared test vector in CustomerMcpProposalTest pins it),
 * so a store bug or a tampered response cannot make the device sign one thing while another runs.
 */
internal data class CustomerMcpProposal(
    val id: UUID,
    val partyId: UUID,
    val status: String,
    val mcpClientId: String,
    val operationType: String,
    val debtorAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val amount: String,
    val currency: String,
    val reference: String?,
    val payloadSha256: String,
    val approvalRequestId: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: String? = null,
) {
    /**
     * Null when [customerParty] may approve this proposal now with [suppliedHash]; else a refusal
     * code. PENDING is approvable; APPROVED with no payment yet is a RETRY of a payment that failed
     * after approval (a fresh SCA is still required, and the proposal-id idempotency key means it
     * can never pay twice). Expiry is checked HERE against the bank's clock, not left to a sweep.
     */
    fun refusalFor(customerParty: UUID, suppliedHash: String, now: OffsetDateTime): String? = when {
        partyId != customerParty -> "PROPOSAL_NOT_FOUND"
        !(status == "PENDING" || (status == "APPROVED" && executedPaymentId == null)) -> "PROPOSAL_NOT_PENDING"
        !now.isBefore(expiresAt) -> "PROPOSAL_EXPIRED"
        approvalRequestId != APPROVAL_PREFIX + id -> "PROPOSAL_MALFORMED"
        !safeText(creditorName) || (reference != null && !safeText(reference)) -> "PROPOSAL_MALFORMED"
        runCatching { canonicalSha256() }.getOrNull() != payloadSha256 -> "PROPOSAL_HASH_INVALID"
        !MessageDigest.isEqual(suppliedHash.toByteArray(), payloadSha256.toByteArray()) -> "PROPOSAL_HASH_MISMATCH"
        else -> null
    }

    fun canonicalSha256(): String = canonicalSha256(
        id, partyId, operationType, debtorAccountId, creditorIban, creditorName, amount, currency, reference,
    )

    /** The app's own domestic body, built ONLY from the stored instruction. */
    fun domesticBody(mapper: ObjectMapper): String = mapper.writeValueAsString(
        buildMap {
            put("debtorAccountId", debtorAccountId.toString())
            put("amount", amount)
            put("currency", currency)
            put("creditorAccountNumber", creditorIban)
            put("creditorName", creditorName)
            reference?.let { put("reference", it) }
        },
    )

    fun sepaBody(mapper: ObjectMapper): String = mapper.writeValueAsString(
        buildMap {
            put("debtorAccountId", debtorAccountId.toString())
            put("amount", amount)
            put("currency", currency)
            put("creditorIban", creditorIban)
            put("creditorName", creditorName)
            reference?.let { put("reference", it) }
        },
    )

    companion object {
        const val APPROVAL_PREFIX = "mcp-proposal:"

        /** Same rule as the store (ADR-0383 D7): NFC, no Cc/Cf/Zl/Zp/Cs/Co/Cn, no markup, no `://`. */
        private val UNSAFE = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Cs}\\p{Co}\\p{Cn}<>\\[\\]`]|://")

        fun safeText(value: String): Boolean =
            Normalizer.isNormalized(value, Normalizer.Form.NFC) && !UNSAFE.containsMatchIn(value)
        private const val DOMAIN = "openbank.payment-proposal.v1"

        @Suppress("LongParameterList")
        fun canonicalSha256(
            id: UUID,
            partyId: UUID,
            operationType: String,
            debtorAccountId: UUID,
            creditorIban: String,
            creditorName: String,
            amount: String,
            currency: String,
            reference: String?,
        ): String {
            val canonicalAmount = BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
            val form = buildString {
                append(DOMAIN).append('\n')
                append("proposalId=").append(id).append('\n')
                append("partyId=").append(partyId).append('\n')
                append("operationType=").append(operationType).append('\n')
                append("debtorAccountId=").append(debtorAccountId).append('\n')
                append("creditorIban=").append(creditorIban).append('\n')
                append("creditorName=").append(creditorName).append('\n')
                append("amount=").append(canonicalAmount).append('\n')
                append("currency=").append(currency).append('\n')
                append("reference=").append(reference.orEmpty()).append('\n')
            }
            return MessageDigest.getInstance("SHA-256").digest(form.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /** Null for anything that is not a well-formed proposal (treated as not found). */
        fun parse(mapper: ObjectMapper, json: String): CustomerMcpProposal? = runCatching {
            val n = mapper.readTree(json)
            fun t(f: String): String = n.path(f).takeIf { it.isTextual }?.asText() ?: error("missing $f")
            CustomerMcpProposal(
                id = UUID.fromString(t("id")),
                partyId = UUID.fromString(t("partyId")),
                status = t("status"),
                mcpClientId = t("mcpClientId"),
                operationType = t("operationType"),
                debtorAccountId = UUID.fromString(t("debtorAccountId")),
                creditorIban = t("creditorIban"),
                creditorName = t("creditorName"),
                amount = t("amount"),
                currency = t("currency"),
                reference = n.path("reference").takeIf { it.isTextual }?.asText(),
                payloadSha256 = t("payloadSha256"),
                approvalRequestId = t("approvalRequestId"),
                expiresAt = OffsetDateTime.parse(t("expiresAt")),
                executedPaymentId = n.path("executedPaymentId").takeIf { it.isTextual }?.asText(),
            )
        }.getOrNull()
    }
}
