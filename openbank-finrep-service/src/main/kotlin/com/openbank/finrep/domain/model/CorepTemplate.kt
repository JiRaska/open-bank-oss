// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.time.LocalDate

/**
 * One rendered COREP template (e.g. C 01.00 Own Funds).
 *
 * This is a structured, well-typed representation of the template's cells — NOT an EBA
 * XBRL/DPM taxonomy rendering. XBRL/DPM output and the ČNB transmission channel are explicitly
 * out of scope for this increment (ADR-0097 Phase 2, first increment); see the ADR delivery
 * note for the current status.
 *
 * [hasDataGaps] is true when one or more cells are flagged [CorepCell.isDataGap] — i.e. the
 * platform does not yet have the underlying data (e.g. capital-structure GL accounts) to
 * produce a real, attested value for that row. A report with data gaps is still fully rendered
 * (every row present, gap cells explicit zeros), never silently truncated.
 */
data class CorepTemplate(val templateId: String, val period: LocalDate, val cells: List<CorepCell>) {
    val hasDataGaps: Boolean get() = cells.any { it.isDataGap }
}
