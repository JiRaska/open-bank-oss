// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.Pacs008Reader
import com.openbank.libs.iso20022.ReceivedCreditTransfer
import com.openbank.libs.iso20022.SettlementMethod
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** pacs.008 credit transfer: build, read back, validate against the vendored XSD. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class Iso20022Bench {
    private val builder = Pacs008Builder()
    private val reader = Pacs008Reader()
    private val validator = Iso20022Validator.forSchema("pacs.008.001.08.xsd")
    private val instruction = CreditTransferInstruction(
        messageId = "OB-MSG-0001",
        creationDateTime = TIMESTAMP,
        interbankSettlementDate = TIMESTAMP,
        endToEndId = "E2E-0001",
        transactionId = "TX-0001",
        amount = BigDecimal("12.34"),
        currency = "EUR",
        chargeBearer = ChargeBearer.SLEV,
        settlementMethod = SettlementMethod.CLRG,
        debtorName = "Alice Debtor",
        debtorIban = "DE89370400440532013000",
        debtorAgentBic = "COBADEFFXXX",
        creditorAgentBic = "BNPAFRPPXXX",
        creditorName = "Bob Creditor",
        creditorIban = "FR1420041010050500013M02606",
        remittanceInfo = "Invoice 2026-0042",
    )
    private val xml: String = builder.build(instruction).also {
        check(validator.validate(it) is Iso20022ValidationResult.Valid) { "benchmark message must be schema-valid" }
    }

    @Benchmark
    fun buildPacs008(): String = builder.build(instruction)

    @Benchmark
    fun readPacs008(): ReceivedCreditTransfer = reader.read(xml)

    @Benchmark
    fun validatePacs008(): Iso20022ValidationResult = validator.validate(xml)

    private companion object {
        val TIMESTAMP: OffsetDateTime = OffsetDateTime.of(2026, 6, 22, 10, 15, 30, 0, ZoneOffset.UTC)
    }
}
