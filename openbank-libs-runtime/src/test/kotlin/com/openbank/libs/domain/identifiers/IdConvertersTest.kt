// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.identifiers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Every typesafe-ID JPA converter must round-trip through the raw [UUID] column type and
 * must pass `null` through unchanged — Hibernate calls these converters on nullable
 * foreign-key columns, so a converter that NPEs on null breaks persistence fleet-wide.
 */
class IdConvertersTest {

    @Test
    fun `AccountIdConverter round-trips and passes null through`() {
        val converter = AccountIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(AccountId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(AccountId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `TransactionIdConverter round-trips and passes null through`() {
        val converter = TransactionIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(TransactionId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(TransactionId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `PartyIdConverter round-trips and passes null through`() {
        val converter = PartyIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(PartyId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(PartyId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `CardIdConverter round-trips and passes null through`() {
        val converter = CardIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(CardId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(CardId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `DisputeIdConverter round-trips and passes null through`() {
        val converter = DisputeIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(DisputeId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(DisputeId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `OrderIdConverter round-trips and passes null through`() {
        val converter = OrderIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(OrderId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(OrderId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `ConsentIdConverter round-trips and passes null through`() {
        val converter = ConsentIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(ConsentId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(ConsentId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `PaymentIdConverter round-trips and passes null through`() {
        val converter = PaymentIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(PaymentId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(PaymentId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `CaseIdConverter round-trips and passes null through`() {
        val converter = CaseIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(CaseId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(CaseId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `LoanApplicationIdConverter round-trips and passes null through`() {
        val converter = LoanApplicationIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(LoanApplicationId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(LoanApplicationId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `LoanIdConverter round-trips and passes null through`() {
        val converter = LoanIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(LoanId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(LoanId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }

    @Test
    fun `CollateralIdConverter round-trips and passes null through`() {
        val converter = CollateralIdConverter()
        val id = UUID.randomUUID()
        assertThat(converter.convertToDatabaseColumn(CollateralId(id))).isEqualTo(id)
        assertThat(converter.convertToEntityAttribute(id)).isEqualTo(CollateralId(id))
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }
}
