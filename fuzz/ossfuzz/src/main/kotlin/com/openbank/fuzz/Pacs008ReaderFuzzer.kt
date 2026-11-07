// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.Pacs008ParseException
import com.openbank.libs.iso20022.Pacs008Reader
import com.openbank.libs.iso20022.ReceivedCreditTransfer
import com.openbank.libs.iso20022.SettlementMethod
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Jazzer fuzz target for the ISO 20022 pacs.008 reader and validator: they consume XML
 * produced OUTSIDE the trust boundary (clearing / simulator inbound credit transfers).
 *
 * Property: for ANY input, read() either returns a ReceivedCreditTransfer or throws the
 * typed Pacs008ParseException, and validate() returns a validation result without throwing.
 * Afterwards, the same instances must accept a freshly built known-good message with every
 * recovered field intact. Unexpected throwables and failed recovery checks are findings.
 */
object Pacs008ReaderFuzzer {
    private val reader = Pacs008Reader()
    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val recoveryInstruction = CreditTransferInstruction(
        messageId = "FUZZ-RECOVERY-MSG",
        creationDateTime = OffsetDateTime.parse("2026-01-15T10:00:00Z"),
        interbankSettlementDate = OffsetDateTime.parse("2026-01-15T10:00:00Z"),
        endToEndId = "FUZZ-RECOVERY-E2E",
        transactionId = "FUZZ-RECOVERY-TX",
        amount = BigDecimal("12.34"),
        currency = "EUR",
        chargeBearer = ChargeBearer.SLEV,
        settlementMethod = SettlementMethod.CLRG,
        debtorName = "Synthetic Debtor",
        debtorIban = "DE89370400440532013000",
        debtorAgentBic = "COBADEFFXXX",
        creditorAgentBic = "BNPAFRPPXXX",
        creditorName = "Synthetic & Creditor",
        creditorIban = "FR1420041010050500013M02606",
        remittanceInfo = "Synthetic recovery <check>",
    )
    private val expectedRecovery = ReceivedCreditTransfer(
        messageId = recoveryInstruction.messageId,
        endToEndId = recoveryInstruction.endToEndId,
        transactionId = recoveryInstruction.transactionId,
        amount = recoveryInstruction.amount,
        currency = recoveryInstruction.currency,
        creditorName = recoveryInstruction.creditorName,
        creditorIban = recoveryInstruction.creditorIban,
        creditorAgentBic = recoveryInstruction.creditorAgentBic,
        debtorIban = recoveryInstruction.debtorIban,
    )

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) = verify(data.consumeRemainingAsString())

    /** Shared oracle for coverage-guided fuzzing and deterministic corpus regression tests. */
    @JvmStatic
    fun verify(xml: String) {
        try {
            reader.read(xml)
        } catch (_: Pacs008ParseException) {
            // typed, expected rejection — fine
        }
        validator.validate(xml)

        // Build after the arbitrary input, rather than caching XML, to exercise fresh output
        // document construction as well as reader/validator recovery after rejected input.
        val recoveryXml = builder.build(recoveryInstruction)
        check(validator.validate(recoveryXml) is Iso20022ValidationResult.Valid) {
            "validator rejected the known-good recovery message"
        }
        check(reader.read(recoveryXml) == expectedRecovery) {
            "reader did not recover the exact known-good fields"
        }
    }
}
