// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode

/** Product-catalogue failures exposed through the shared typed error model (ADR-0326). */
enum class CatalogErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    PRODUCT_NOT_FOUND(ErrorCategory.NOT_FOUND, "The product does not exist"),
    PRODUCT_CODE_ALREADY_EXISTS(ErrorCategory.CONFLICT, "The product code already exists"),
    ;

    override val code: String get() = name
}
