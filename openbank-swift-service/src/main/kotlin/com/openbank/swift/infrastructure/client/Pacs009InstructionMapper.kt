// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.libs.iso20022.ClearingSystem
import com.openbank.libs.iso20022.InstitutionCreditTransferInstruction
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.InstitutionTransferRail
import com.openbank.swift.domain.institution.Mt202Mapper
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Maps a bank-own [InstitutionTransfer] to the `pacs.009` (CORE) instruction for its rail:
 *
 * | rail | SttlmMtd | ClrSys | InstdAgt |
 * |---|---|---|---|
 * | CERTIS  | CLRG | Prtry `CERTIS` | creditor (direct participant) |
 * | TARGET2 | CLRG | Cd `TGT`      | creditor (direct participant) |
 * | SWIFT   | INDA | —             | intermediary when given, else creditor |
 *
 * `InstgAgt` and `Dbtr` are this bank (the debtor BIC). On the RTGS routes an intermediary is
 * still carried as `IntrmyAgt1` but the instructed agent stays the creditor, since both parties
 * are participants of the same system. `MsgId`/`InstrId` are the MT202 `:20:` reference, the
 * `EndToEndId` and `TxId` are the payment id (hyphens dropped: Max35Text), and the UETR is the transfer's own.
 */
object Pacs009InstructionMapper {
    private const val MAX_35 = 35

    fun instruction(
        t: InstitutionTransfer,
        now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): InstitutionCreditTransferInstruction {
        val reference = Mt202Mapper.reference(t)
        val (method, clearingSystem) = when (t.rail) {
            InstitutionTransferRail.CERTIS -> SettlementMethod.CLRG to ClearingSystem(proprietary = "CERTIS")
            InstitutionTransferRail.TARGET2 -> SettlementMethod.CLRG to ClearingSystem(code = "TGT")
            InstitutionTransferRail.SWIFT -> SettlementMethod.INDA to null
        }
        val instructed = if (t.rail ==
            InstitutionTransferRail.SWIFT
        ) {
            t.intermediaryBic ?: t.creditor.bic
        } else {
            t.creditor.bic
        }
        return InstitutionCreditTransferInstruction(
            messageId = "FI-$reference".take(MAX_35),
            creationDateTime = now,
            interbankSettlementDate = t.valueDate,
            instructionId = reference,
            endToEndId = compactId(t),
            transactionId = compactId(t),
            uetr = t.uetr,
            amount = t.amount.amount,
            currency = t.currency,
            settlementMethod = method,
            clearingSystem = clearingSystem,
            instructingAgentBic = t.debtor.bic,
            instructedAgentBic = instructed,
            intermediaryAgentBic = t.intermediaryBic?.takeIf { it != instructed },
            debtorBic = t.debtor.bic,
            debtorAccount = t.debtor.accountNumber,
            creditorBic = t.creditor.bic,
            creditorName = t.creditorName,
            creditorAccount = t.creditor.accountNumber,
            remittanceInfo = t.remittanceInformation,
        )
    }

    /** The payment id without hyphens — 32 characters, inside ISO 20022 `Max35Text`. */
    fun compactId(t: InstitutionTransfer): String = t.paymentId.toString().replace("-", "")

    /** The simulator's route path segment for [rail]. */
    fun routeOf(rail: InstitutionTransferRail): String = rail.name.lowercase()
}
