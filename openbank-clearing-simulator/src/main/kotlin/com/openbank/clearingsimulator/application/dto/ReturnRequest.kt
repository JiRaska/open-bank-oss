// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearingsimulator.application.dto

import java.math.BigDecimal

/**
 * Request body for the POST /api/v1/clearing/returns endpoint.
 * The simulator uses this to generate a pacs.004 R-transaction and forward it to
 * the sepa-payment return handler.
 */
data class ReturnRequest(
    val originalEndToEndId: String,
    val originalTransactionId: String?,
    val amount: BigDecimal,
    val currency: String,
    val returnReasonCode: String?, // e.g. AC04, MD06, AM09
    val additionalInfo: String? = null,
)
