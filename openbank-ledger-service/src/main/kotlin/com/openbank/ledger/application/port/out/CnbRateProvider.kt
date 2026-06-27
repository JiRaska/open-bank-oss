// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import java.math.BigDecimal

/**
 * Outbound port for reading the ČNB central-bank fixing from `openbank-fx-service` (FX rates are
 * that service's bounded context — ADR-0046). Returns the statutory CZK-per-unit rate used for the
 * daily mark-to-ČNB revaluation.
 */
interface CnbRateProvider {

    /** Latest ČNB fixing as CZK per 1 unit of [base] (against CZK), or `null` if none is published. */
    suspend fun cnbRate(base: String): BigDecimal?
}
