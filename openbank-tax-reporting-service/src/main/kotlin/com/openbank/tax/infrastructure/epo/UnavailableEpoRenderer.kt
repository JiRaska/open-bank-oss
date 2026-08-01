// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.epo

import com.openbank.tax.application.port.out.EpoRendererPort
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxFilingRecord
import jakarta.enterprise.context.ApplicationScoped

/**
 * The bound [EpoRendererPort] until the real GFŘ EPO schema is available (ADR-0180 v1).
 *
 * It reports `available = false` and throws if called, rather than emitting a best-effort XML.
 * That is the point: the EPO XSD for *Vyúčtování daně vybírané srážkou* is a specific published
 * schema, and a guess at it would produce a file that passes every gate in this repo and is wrong
 * at the finanční úřad. A wrong tax return is worse than no tax return — a missing filing is a
 * visible gap, a wrong one is a filed falsehood.
 *
 * Everything else in this service works without it. The aggregation is the part that had no owner
 * (ADR-0038 self-flagged that gap), and an operator can read the assembled totals off the API and
 * key them into the EPO portal today, then record the reference. `/export-capability` states this
 * plainly so no caller infers a rendering that does not exist.
 */
@ApplicationScoped
class UnavailableEpoRenderer : EpoRendererPort {
    override val available: Boolean = false

    override suspend fun renderVyuctovani(
        filing: TaxFilingRecord,
        remittances: List<ObservedRemittance>,
    ): ByteArray = throw UnsupportedOperationException(
        "EPO XML rendering is not built (ADR-0180 v1). Assembled totals for ${filing.period.label} " +
            "are available on the API; submit via the EPO portal or datová schránka and record the " +
            "reference with POST /api/v1/tax/filings/${filing.period.label}/filed.",
    )
}
