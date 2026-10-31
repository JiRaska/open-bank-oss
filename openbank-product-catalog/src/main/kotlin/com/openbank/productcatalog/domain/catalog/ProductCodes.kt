// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain.catalog

/**
 * ADR-0352 §7 (phase 5b): the ONE mapping between a product IR's `productCode` (`SAVINGS-FIXED`, the
 * openbank-products spelling) and the catalog offering that owns it (`SAVINGS_FIXED`; an offering code
 * admits no `-`). The offering is the record of which product a revision is; the IR must agree with it.
 */
object ProductCodes {
    fun offeringCode(irProductCode: String): String = irProductCode.replace('-', '_')
}
