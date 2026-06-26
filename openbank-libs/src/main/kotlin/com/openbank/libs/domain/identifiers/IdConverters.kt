// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.identifiers

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.util.UUID

/**
 * JPA converters for the typesafe IDs. Annotate the entity column with
 * `@Convert(converter = AccountIdConverter::class)` (or set `autoApply = true` per project).
 *
 * Note: there is no JVM-level way to express a single generic AttributeConverter for all IDs
 * because the type variable is erased at runtime, so each ID needs its own converter.
 */
@Converter
class AccountIdConverter : AttributeConverter<AccountId, UUID> {
    override fun convertToDatabaseColumn(attribute: AccountId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): AccountId? = dbData?.let(::AccountId)
}

@Converter
class TransactionIdConverter : AttributeConverter<TransactionId, UUID> {
    override fun convertToDatabaseColumn(attribute: TransactionId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): TransactionId? = dbData?.let(::TransactionId)
}

@Converter
class PartyIdConverter : AttributeConverter<PartyId, UUID> {
    override fun convertToDatabaseColumn(attribute: PartyId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): PartyId? = dbData?.let(::PartyId)
}

@Converter
class CardIdConverter : AttributeConverter<CardId, UUID> {
    override fun convertToDatabaseColumn(attribute: CardId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): CardId? = dbData?.let(::CardId)
}

@Converter
class DisputeIdConverter : AttributeConverter<DisputeId, UUID> {
    override fun convertToDatabaseColumn(attribute: DisputeId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): DisputeId? = dbData?.let(::DisputeId)
}

@Converter
class OrderIdConverter : AttributeConverter<OrderId, UUID> {
    override fun convertToDatabaseColumn(attribute: OrderId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): OrderId? = dbData?.let(::OrderId)
}

@Converter
class ConsentIdConverter : AttributeConverter<ConsentId, UUID> {
    override fun convertToDatabaseColumn(attribute: ConsentId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): ConsentId? = dbData?.let(::ConsentId)
}

@Converter
class PaymentIdConverter : AttributeConverter<PaymentId, UUID> {
    override fun convertToDatabaseColumn(attribute: PaymentId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): PaymentId? = dbData?.let(::PaymentId)
}

@Converter
class CaseIdConverter : AttributeConverter<CaseId, UUID> {
    override fun convertToDatabaseColumn(attribute: CaseId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): CaseId? = dbData?.let(::CaseId)
}
