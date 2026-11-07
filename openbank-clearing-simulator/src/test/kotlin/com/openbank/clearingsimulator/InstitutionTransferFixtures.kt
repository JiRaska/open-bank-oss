// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator

import com.openbank.libs.iso20022.ClearingSystem
import com.openbank.libs.iso20022.InstitutionCreditTransferInstruction
import com.openbank.libs.iso20022.Pacs009Builder
import com.openbank.libs.iso20022.SettlementMethod
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A valid `pacs.009` per route, built with the production builder the rail uses. */
object InstitutionTransferFixtures {
    fun pacs009(
        amount: String = "1000.00",
        currency: String = "EUR",
        clearingSystem: ClearingSystem? = ClearingSystem(code = "TGT"),
        uetr: String? = null,
    ): String = Pacs009Builder().build(
        InstitutionCreditTransferInstruction(
            messageId = "OB-FI-0001",
            creationDateTime = OffsetDateTime.of(2026, 10, 19, 9, 0, 0, 0, ZoneOffset.UTC),
            interbankSettlementDate = LocalDate.of(2026, 10, 19),
            instructionId = null,
            endToEndId = "E2E-FI-0001",
            transactionId = "TX-FI-0001",
            uetr = uetr,
            amount = BigDecimal(amount),
            currency = currency,
            settlementMethod = if (clearingSystem == null) SettlementMethod.INDA else SettlementMethod.CLRG,
            clearingSystem = clearingSystem,
            instructingAgentBic = "OPENCZPPXXX",
            instructedAgentBic = "BNPAFRPPXXX",
            intermediaryAgentBic = null,
            debtorBic = "OPENCZPPXXX",
            debtorAccount = "CZ6508000000192000145399",
            creditorBic = "BNPAFRPPXXX",
            creditorName = "Counterparty Bank",
            creditorAccount = "FR1420041010050500013M02606",
            remittanceInfo = "deal reference",
        ),
    )

    fun target2(amount: String = "1000.00") = pacs009(amount, "EUR", ClearingSystem(code = "TGT"))
    fun certis(amount: String = "1000.00") = pacs009(amount, "CZK", ClearingSystem(proprietary = "CERTIS"))
    fun swift(amount: String = "1000.00", currency: String = "USD") = pacs009(amount, currency, null)
}
