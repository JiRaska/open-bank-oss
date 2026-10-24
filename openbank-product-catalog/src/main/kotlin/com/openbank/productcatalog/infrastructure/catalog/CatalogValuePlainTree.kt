// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.openbank.productcatalog.domain.catalog.CatalogValue

/**
 * The IR document as plain Kotlin values (`Map`, `List`, `String`, `BigDecimal`, `Boolean`), the
 * shape `ProductIrParser.fromTree` reads — the same values `IrJson` yields from text, so the
 * catalog's copy and a file on disk parse to one revision and one hash (ADR-0352 §4).
 */
fun CatalogValue.toPlainTree(): Any? = when (this) {
    CatalogValue.NullValue -> null
    is CatalogValue.BooleanValue -> value
    is CatalogValue.TextValue -> value
    is CatalogValue.DecimalValue -> value
    is CatalogValue.ArrayValue -> values.map { it.toPlainTree() }
    is CatalogValue.ObjectValue -> values.mapValues { it.value.toPlainTree() }
}
