// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.identifiers

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.util.UUID

/** JPA converters for the lending bounded-context identifiers (ADR-0028). */

@Converter
class LoanApplicationIdConverter : AttributeConverter<LoanApplicationId, UUID> {
    override fun convertToDatabaseColumn(attribute: LoanApplicationId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): LoanApplicationId? = dbData?.let(::LoanApplicationId)
}

@Converter
class LoanIdConverter : AttributeConverter<LoanId, UUID> {
    override fun convertToDatabaseColumn(attribute: LoanId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): LoanId? = dbData?.let(::LoanId)
}

@Converter
class CollateralIdConverter : AttributeConverter<CollateralId, UUID> {
    override fun convertToDatabaseColumn(attribute: CollateralId?): UUID? = attribute?.value
    override fun convertToEntityAttribute(dbData: UUID?): CollateralId? = dbData?.let(::CollateralId)
}
