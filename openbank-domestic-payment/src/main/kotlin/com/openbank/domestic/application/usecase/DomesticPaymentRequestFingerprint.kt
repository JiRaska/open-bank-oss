// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/**
 * Canonical request binding for `Idempotency-Key`.
 *
 * The digest is deliberately computed from the semantic, normalized command rather than serialized
 * JSON: JSON property order, decimal scale and harmless surrounding whitespace must not make an
 * otherwise identical retry look different. Length-prefixing every nullable field makes the byte
 * stream unambiguous without relying on a delimiter that customer data could contain.
 */
internal object DomesticPaymentRequestFingerprint {
    private const val FORMAT = "openbank-domestic-payment-create-v1"

    fun normalize(command: CreateDomesticPaymentCommand): CreateDomesticPaymentCommand = command.copy(
        debtorAccountNumber = command.debtorAccountNumber.trim(),
        debtorBankCode = command.debtorBankCode.trim(),
        debtorName = command.debtorName.trim(),
        creditorAccountNumber = command.creditorAccountNumber.trim(),
        creditorBankCode = command.creditorBankCode.trim(),
        creditorName = command.creditorName.trim(),
        currency = command.currency.trim().uppercase(Locale.ROOT),
        variableSymbol = command.variableSymbol.normalizedOptional(),
        specificSymbol = command.specificSymbol.normalizedOptional(),
        constantSymbol = command.constantSymbol.normalizedOptional(),
        messageForPayee = command.messageForPayee.normalizedOptional(),
        technicalAccountCode = command.technicalAccountCode.normalizedOptional(),
        statementLabel = command.statementLabel.normalizedOptional(),
        endToEndId = command.endToEndId.normalizedOptional(),
        actorScope = command.actorScope.normalizedOptional(),
    )

    fun sha256(command: CreateDomesticPaymentCommand): String {
        val normalized = normalize(command)
        val digest = MessageDigest.getInstance("SHA-256")

        digest.add(FORMAT)
        digest.add(normalized.idempotencyKey)
        digest.add(normalized.debtorAccountId.toString())
        digest.add(normalized.debtorAccountNumber)
        digest.add(normalized.debtorBankCode)
        digest.add(normalized.debtorName)
        digest.add(normalized.creditorAccountNumber)
        digest.add(normalized.creditorBankCode)
        digest.add(normalized.creditorName)
        digest.add(normalized.amount.stripTrailingZeros().toPlainString())
        digest.add(normalized.currency)
        digest.add(normalized.variableSymbol)
        digest.add(normalized.specificSymbol)
        digest.add(normalized.constantSymbol)
        digest.add(normalized.messageForPayee)
        digest.add(normalized.priority.name)
        // transferScope is an ignored client hint; the service derives the effective scope. An
        // input that cannot affect the payment must not make two otherwise equal retries conflict.
        digest.add(normalized.technicalAccountCode)
        digest.add(normalized.statementLabel)
        digest.add(normalized.endToEndId)
        digest.add(normalized.actorId?.toString())
        digest.add(normalized.actorScope)
        digest.add(normalized.delegationId?.toString())
        digest.add(normalized.reservationId?.toString())
        digest.add(normalized.synthetic.toString())

        return HexFormat.of().formatHex(digest.digest())
    }

    private fun String?.normalizedOptional(): String? = this?.trim()?.ifBlank { null }

    private fun MessageDigest.add(value: String?) {
        if (value == null) {
            update(NULL_MARKER)
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(VALUE_MARKER)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private val NULL_MARKER = byteArrayOf(0)
    private val VALUE_MARKER = byteArrayOf(1)
}
