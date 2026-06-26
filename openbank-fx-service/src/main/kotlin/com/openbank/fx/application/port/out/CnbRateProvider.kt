// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.application.port.out

import java.time.LocalDate

/**
 * Outbound port abstracting retrieval of the ČNB central-bank exchange-rate fixing
 * (kurz devizového trhu) raw text feed. The production adapter is a resilient RestClient to the
 * ČNB daily feed; the port keeps the ingestion use-case free of HTTP concerns and trivially testable.
 */
interface CnbRateProvider {

    /**
     * Fetches the published fixing text for [date] (the bank business day). When [date] is `null`
     * the feed returns the latest published fixing. The returned string is the raw feed body, to be
     * handed to [com.openbank.fx.domain.cnb.CnbFixingParser].
     */
    suspend fun fetchFixing(date: LocalDate?): String
}
