// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator

import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.SettlementMethod
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Old image/deployment semantics: no database or receipt credential exists. */
@QuarkusTest
class LegacyNoDatabaseBootIT {
    @Test
    @TestSecurity(user = "rail", roles = ["ROLE_API"])
    fun `legacy endpoint boots and settles while receipt endpoint stays disabled`() {
        val xml = Pacs008Builder().build(
            CreditTransferInstruction(
                messageId = "LEGACY-MSG-1",
                creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
                interbankSettlementDate = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC),
                endToEndId = "LEGACY-E2E-1",
                transactionId = "LEGACY-TX-1",
                amount = BigDecimal("12.34"),
                currency = "EUR",
                chargeBearer = ChargeBearer.SLEV,
                settlementMethod = SettlementMethod.CLRG,
                debtorName = "Test Debtor",
                debtorIban = "DE89370400440532013000",
                debtorAgentBic = "COBADEFFXXX",
                creditorAgentBic = "BNPAFRPPXXX",
                creditorName = "Test Creditor",
                creditorIban = "FR1420041010050500013M02606",
                remittanceInfo = "Sandbox fixture",
            ),
        )
        val legacy = Given {
            contentType("application/xml")
            body(xml)
        }
            .When { post("/api/v1/clearing/credit-transfers") }
            .Then { statusCode(200) }.extract().body().asString()
        assertThat(legacy).contains("<TxSts>ACSC</TxSts>")

        Given {
            contentType("application/xml")
            body(xml)
        }
            .When { post("/api/v1/clearing/receipted-credit-transfers/SEPA_INSTANT/legacy-1") }
            .Then { statusCode(503) }
    }
}
