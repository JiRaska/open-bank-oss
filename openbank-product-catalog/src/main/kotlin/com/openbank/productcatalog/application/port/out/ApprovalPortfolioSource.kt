// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application.port.out

/**
 * ADR-0352 §7: the server-defined golden portfolio a product's approval backtest runs over. Only a
 * backtest over THIS portfolio is evidence that the approver saw the change; a portfolio the approver
 * supplies may still be run, but it is the approver's choice of what to look at and unlocks nothing.
 */
interface ApprovalPortfolioSource {
    /** The portfolio for [productCode], else for [currency]; null when the server defines none. */
    fun find(productCode: String, currency: String): ApprovalPortfolio?
}

/** [name] is the file it came from; [sha256] covers its exact bytes so the evidence can be re-checked. */
class ApprovalPortfolio(val name: String, val tree: Any?, val sha256: String)
