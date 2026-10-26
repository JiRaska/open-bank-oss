// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.model

/** Pinned EBA Reporting Framework metadata used by the XBRL-CSV preflight (#5914). */
object EbaReportingFramework42 {
    const val REPORTING_FRAMEWORK_VERSION = "4.2"
    const val DPM_VERSION = "4.2.1"
    const val TAXONOMY_VERSION = "4.2.0.0"

    /**
     * Emitted cells (`templateId/rowRef/colRef`) whose datapoint id is not verified against the
     * annotated templates (#13603). Preflight blocks any return that contains one.
     */
    val UNVERIFIED_DATAPOINT_CELLS: Set<String> = setOf(
        "F01.01/r0030/c0010",
        "F01.02/r0130/c0010",
        "F01.02/r0150/c0010",
    )
}
