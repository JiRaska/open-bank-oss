// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.domain.render

import com.openbank.statement.domain.model.StatementFormat
import com.openbank.statement.domain.model.StatementModel

/**
 * Dispatches a [StatementModel] to the requested format. Each branch is a pure projection of the same
 * audited model (ADR-0035 §C) — a number on the PDF is the same field serialised into camt.053.
 */
object StatementRenderer {

    data class Rendered(val format: StatementFormat, val contentType: String, val body: String)

    fun render(model: StatementModel, format: StatementFormat): Rendered = when (format) {
        StatementFormat.CAMT_053 -> Rendered(format, "application/xml", Camt053Renderer.render(model))
        StatementFormat.MT940 -> Rendered(format, "text/plain", Mt940Renderer.render(model))
        StatementFormat.PDF -> Rendered(format, "text/plain", PdfRenderer.render(model))
    }
}
