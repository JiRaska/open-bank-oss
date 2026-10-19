// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.ReleaseValidation
import com.openbank.swift.domain.institution.ReleasedPayment
import com.openbank.swift.domain.institution.ReleasedPaymentValidator
import com.openbank.swift.infrastructure.kafka.TreasuryPaymentReleasedConsumer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Contract 9.1 payloads for tests — a valid release per rail, as treasury would publish it. */
object InstitutionTransferFixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 19)
    val NOW: Instant = Instant.parse("2026-10-19T08:00:00Z")
    private val mapper = ObjectMapper()

    fun release(
        rail: String = "TARGET2",
        currency: String = if (rail == "CERTIS") {
            "CZK"
        } else if (rail == "TARGET2") {
            "EUR"
        } else {
            "USD"
        },
        amount: String = "1000000.00",
        paymentId: UUID = UUID.randomUUID(),
        extra: Map<String, Any?> = emptyMap(),
        drop: Set<String> = emptySet(),
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "paymentId" to paymentId.toString(),
            "idempotencyKey" to "treasury:${UUID.randomUUID()}:${UUID.randomUUID()}:payment",
            "rail" to rail,
            "currency" to currency,
            "amount" to amount,
            "valueDate" to "2026-10-19",
            "debtor" to
                mapOf(
                    "bic" to "OPENCZPPXXX",
                    "accountNumber" to "CZ6508000000192000145399",
                    "glAccountingClass" to "NOSTRO",
                ),
            "creditor" to
                mapOf(
                    "bic" to "BNPAFRPPXXX",
                    "accountNumber" to "FR1420041010050500013M02606",
                    "name" to "Counterparty Bank",
                ),
            "intermediaryBic" to null,
            "remittanceInformation" to "MM deal settlement",
            "dealIds" to listOf(UUID.randomUUID().toString()),
            "flowIds" to listOf(UUID.randomUUID().toString()),
            "releasedBy" to "treasury-officer",
            "releasedAt" to "2026-10-19T07:59:00Z",
            "synthetic" to true,
        )
        drop.forEach { m.remove(it) }
        m.putAll(extra)
        return m
    }

    fun json(m: Map<String, Any?>): String = mapper.writeValueAsString(m)

    fun parsed(m: Map<String, Any?>): ReleasedPayment = TreasuryPaymentReleasedConsumer.parse(mapper.readTree(json(m)))

    fun transfer(m: Map<String, Any?> = release()): InstitutionTransfer {
        val r = (ReleasedPaymentValidator.validate(parsed(m), TODAY, 1) as ReleaseValidation.Valid).release
        return InstitutionTransfer(
            id = r.paymentId, paymentId = r.paymentId, idempotencyKey = r.idempotencyKey, rail = r.rail,
            amount = r.amount, valueDate = r.valueDate, debtor = r.debtor,
            debtorGlAccountingClass = r.debtorGlAccountingClass,
            creditor = r.creditor, creditorName = r.creditorName, intermediaryBic = r.intermediaryBic,
            remittanceInformation = r.remittanceInformation, dealIds = r.dealIds, flowIds = r.flowIds,
            releasedBy = r.releasedBy, releasedAt = r.releasedAt, synthetic = r.synthetic,
            status = com.openbank.swift.domain.institution.InstitutionTransferStatus.ACCEPTED,
            uetr = InstitutionTransfer.uetrFor(r.paymentId), reasonCode = null, reason = null,
            rawPacs009 = null, rawMt202 = null, createdAt = NOW, updatedAt = NOW,
            ssiVersion = r.ssiVersion,
        )
    }
}
