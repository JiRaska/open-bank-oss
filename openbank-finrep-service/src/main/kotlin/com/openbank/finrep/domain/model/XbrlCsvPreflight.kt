// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.time.LocalDate

/**
 * Honest readiness result for a future EBA XBRL-CSV renderer.
 *
 * This is deliberately a preflight, not an export: a bounded preview must never be packaged or
 * labelled as a validated regulatory return. `READY_FOR_RENDERING` only means the mapped source
 * data passes the safeguards implemented here; a renderer and taxonomy validation remain a
 * separate subsequent stage.
 */
data class XbrlCsvPreflight(
    val templateId: String,
    val period: LocalDate,
    val reportingFrameworkVersion: String,
    val dpmVersion: String,
    val taxonomyVersion: String,
    val state: XbrlCsvPreflightState,
    val blockers: List<XbrlCsvBlocker>,
)

enum class XbrlCsvPreflightState {
    READY_FOR_RENDERING,
    BLOCKED,
}

/** A machine-readable safeguard which prevents local XBRL-CSV rendering. */
data class XbrlCsvBlocker(val code: String, val reason: String)
