// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Pacs002ReaderTest {
    private val builder = Pacs002Builder()
    private val reader = Pacs002Reader()

    private fun report(status: PaymentStatus, reasonCode: String? = null) = PaymentStatusReport(
        messageId = "SIM-STS-0001",
        creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 16, 0, 0, ZoneOffset.UTC),
        originalEndToEndId = "E2E-0001",
        originalTransactionId = "TX-0001",
        status = status,
        reasonCode = reasonCode,
        additionalInfo = reasonCode?.let { "reason $it" },
    )

    @Test
    fun `ACSC verdict returns status ACSC and null reasonCode`() {
        val result = reader.read(acscXml("E2E-001"))

        assertThat(result.status).isEqualTo(PaymentStatus.ACSC)
        assertThat(result.reasonCode).isNull()
    }

    @Test
    fun `RJCT verdict returns status RJCT with reasonCode AC04`() {
        val result = reader.read(rjctXml("E2E-002", "AC04"))

        assertThat(result.status).isEqualTo(PaymentStatus.RJCT)
        assertThat(result.reasonCode).isEqualTo("AC04")
    }

    @Test
    fun `originalEndToEndId is extracted`() {
        val result = reader.read(acscXml("MY-E2E-ID-42"))

        assertThat(result.originalEndToEndId).isEqualTo("MY-E2E-ID-42")
    }

    @Test
    fun `missing TxInfAndSts throws Pacs002ParseException`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt></FIToFIPmtStsRpt>
</Document>"""

        assertThatThrownBy { reader.read(xml) }
            .isInstanceOf(Pacs002ParseException::class.java)
            .hasMessageContaining("TxInfAndSts")
    }

    @Test
    fun `missing TxSts throws Pacs002ParseException`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt><TxInfAndSts>
    <OrgnlEndToEndId>E2E-003</OrgnlEndToEndId>
  </TxInfAndSts></FIToFIPmtStsRpt>
</Document>"""

        assertThatThrownBy { reader.read(xml) }
            .isInstanceOf(Pacs002ParseException::class.java)
            .hasMessageContaining("TxSts")
    }

    @Test
    fun `unknown status code throws Pacs002ParseException`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt><TxInfAndSts>
    <OrgnlEndToEndId>E2E-004</OrgnlEndToEndId>
    <TxSts>BOGUS</TxSts>
  </TxInfAndSts></FIToFIPmtStsRpt>
</Document>"""

        assertThatThrownBy { reader.read(xml) }
            .isInstanceOf(Pacs002ParseException::class.java)
            .hasMessageContaining("BOGUS")
    }

    @Test
    fun `XXE DOCTYPE injection is rejected`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt><TxInfAndSts>
    <OrgnlEndToEndId>&xxe;</OrgnlEndToEndId>
    <TxSts>ACSC</TxSts>
  </TxInfAndSts></FIToFIPmtStsRpt>
</Document>"""

        assertThatThrownBy { reader.read(xml) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `round-trips an ACSC status report built by Pacs002Builder`() {
        val received = reader.read(builder.build(report(PaymentStatus.ACSC)))

        assertThat(received.status).isEqualTo(PaymentStatus.ACSC)
        assertThat(received.originalEndToEndId).isEqualTo("E2E-0001")
        assertThat(received.reasonCode).isNull()
    }

    @Test
    fun `round-trips an RJCT status report with reason code built by Pacs002Builder`() {
        val received = reader.read(builder.build(report(PaymentStatus.RJCT, reasonCode = "AC04")))

        assertThat(received.status).isEqualTo(PaymentStatus.RJCT)
        assertThat(received.reasonCode).isEqualTo("AC04")
    }

    private fun acscXml(e2eId: String) = """<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt><TxInfAndSts>
    <OrgnlEndToEndId>$e2eId</OrgnlEndToEndId>
    <TxSts>ACSC</TxSts>
  </TxInfAndSts></FIToFIPmtStsRpt>
</Document>"""

    private fun rjctXml(e2eId: String, code: String) = """<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt><TxInfAndSts>
    <OrgnlEndToEndId>$e2eId</OrgnlEndToEndId>
    <TxSts>RJCT</TxSts>
    <StsRsnInf><Rsn><Cd>$code</Cd></Rsn></StsRsnInf>
  </TxInfAndSts></FIToFIPmtStsRpt>
</Document>"""
}
