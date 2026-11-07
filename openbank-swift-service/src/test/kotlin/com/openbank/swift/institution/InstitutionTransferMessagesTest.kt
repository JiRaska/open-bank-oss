// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs009Builder
import com.openbank.libs.iso20022.Pacs009Reader
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.Mt202Mapper
import com.openbank.swift.infrastructure.client.Pacs009InstructionMapper
import com.openbank.swift.institution.InstitutionTransferFixtures.release
import com.openbank.swift.institution.InstitutionTransferFixtures.transfer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InstitutionTransferMessagesTest {
    private val validator = Iso20022Validator.forSchema(Pacs009Builder.SCHEMA_RESOURCE)
    private val builder = Pacs009Builder()

    private fun pacs009(t: InstitutionTransfer) = builder.build(Pacs009InstructionMapper.instruction(t))

    @Test
    fun `each route builds an XSD-valid pacs_009 with its settlement method and clearing system`() {
        val expected = mapOf(
            "CERTIS" to ("CLRG" to "CERTIS"),
            "TARGET2" to ("CLRG" to "TGT"),
            "SWIFT" to ("INDA" to null),
        )
        for ((rail, sttl) in expected) {
            val t = transfer(release(rail))
            val xml = pacs009(t)
            assertThat(validator.validate(xml)).describedAs(rail).isEqualTo(Iso20022ValidationResult.Valid)
            val read = Pacs009Reader().read(xml)
            assertThat(read.settlementMethod).isEqualTo(sttl.first)
            assertThat(read.clearingSystem).isEqualTo(sttl.second)
            assertThat(read.currency).isEqualTo(t.currency)
            assertThat(read.amount).isEqualByComparingTo(t.amount.amount)
            assertThat(read.instructingAgentBic).isEqualTo("OPENCZPPXXX")
            assertThat(read.creditorBic).isEqualTo("BNPAFRPPXXX")
            assertThat(read.endToEndId).isEqualTo(t.paymentId.toString().replace("-", ""))
            assertThat(read.uetr).isEqualTo(t.uetr)
        }
    }

    @Test
    fun `on the SWIFT route an intermediary becomes the instructed agent, on RTGS it stays an intermediary`() {
        val swift = Pacs009Reader().read(
            pacs009(
                transfer(
                    release(
                        "SWIFT",
                        extra = mapOf(
                            "intermediaryBic" to "CHASUS33XXX",
                        ),
                    ),
                ),
            ),
        )
        assertThat(swift.instructedAgentBic).isEqualTo("CHASUS33XXX")
        val rtgsXml = pacs009(transfer(release("TARGET2", extra = mapOf("intermediaryBic" to "COBADEFFXXX"))))
        assertThat(validator.validate(rtgsXml)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(Pacs009Reader().read(rtgsXml).instructedAgentBic).isEqualTo("BNPAFRPPXXX")
        assertThat(rtgsXml).contains("<IntrmyAgt1>")
    }

    @Test
    fun `a CZK domestic account number is carried as Othr and still validates`() {
        val m = release(
            "CERTIS",
            extra = mapOf(
                "creditor" to
                    mapOf("bic" to "KOMBCZPPXXX", "accountNumber" to "19-2000145399/0100", "name" to "Domestic Bank"),
            ),
        )
        val xml = pacs009(transfer(m))
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
        assertThat(xml).contains("<Othr>", "19-2000145399/0100")
    }

    @Test
    fun `the UETR is a stable UUID v4 per payment`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val uetr = InstitutionTransfer.uetrFor(id)
        assertThat(
            uetr,
        ).isEqualTo(
            InstitutionTransfer.uetrFor(id),
        ).matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
        assertThat(InstitutionTransfer.uetrFor(UUID.randomUUID())).isNotEqualTo(uetr)
    }

    @Test
    fun `the MT202 mapping carries the FIN fields`() {
        val t = transfer(
            release(
                "SWIFT",
                amount = "1234.50",
                extra = mapOf(
                    "intermediaryBic" to "CHASUS33XXX",
                    "remittanceInformation" to "deal ref #42 – settlement",
                ),
            ),
        )
        val mt = Mt202Mapper.render(t)
        assertThat(mt).contains("{2:I202BNPAFRPPXXXXN}", ":20:${Mt202Mapper.reference(t)}", ":21:NONREF")
        assertThat(mt).contains(":32A:261019USD1234,50", ":52A:OPENCZPPXXX", ":56A:CHASUS33XXX", ":57A:BNPAFRPPXXX")
        assertThat(mt).contains(":58A:/FR1420041010050500013M02606\nBNPAFRPPXXX", ":72:/BNF/deal ref  42   settlement")
        assertThat(Mt202Mapper.reference(t)).hasSize(16).matches("[0-9A-F]{16}")
        assertThat(
            Mt202Mapper.render(transfer(release(drop = setOf("remittanceInformation")))),
        ).doesNotContain(":72:", ":56A:")
    }
}
